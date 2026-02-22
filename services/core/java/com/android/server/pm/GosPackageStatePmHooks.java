package com.android.server.pm;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.ext.KnownSystemPackages;
import android.ext.DerivedPackageFlag;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.Slog;

import android.app.AppsScope;
import com.android.internal.pm.parsing.pkg.AndroidPackageInternal;
import com.android.internal.pm.pkg.component.ParsedUsesPermission;
import com.android.server.LocalServices;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.utils.Slogf;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.StringJoiner;

import static android.content.pm.GosPackageState.*;
import static com.android.server.pm.GosPackageStateUtils.parseFlag;

public class GosPackageStatePmHooks {
    private static final String TAG = "GosPackageStatePmHooks";

    static void init(PackageManagerService pm) {
        GosPackageStatePermissions.init(pm);
    }

    @NonNull
    public static GosPackageState getUnfiltered(PackageManagerService pm, String packageName, int userId) {
        return getUnfiltered(pm.snapshotComputer(), packageName, userId);
    }

    @NonNull
    public static GosPackageState getUnfiltered(Computer snapshot, String packageName, int userId) {
        PackageStateInternal psi = snapshot.getPackageStates().get(packageName);
        if (psi == null) {
            return NONE;
        }
        return psi.getUserStateOrDefault(userId).getGosPackageState();
    }

    @NonNull
    public static GosPackageState getFiltered(PackageManagerService pm, int callingUid, int callingPid,
                                              String packageName, int userId) {
        Computer pmComputer = pm.snapshotComputer();
        PackageStateInternal packageState = pmComputer.getPackageStates().get(packageName);
        if (packageState == null) {
            // the package was likely racily uninstalled
            return NONE;
        }
        return getFiltered(pmComputer, packageState,
                packageState.getUserStateOrDefault(userId).getGosPackageState(),
                callingUid, callingPid, userId);
    }

    @NonNull
    public static GosPackageState getFiltered(Computer pmComputer,
                                      PackageStateInternal packageState, GosPackageState gosPs,
                                      int callingUid, int callingPid, int userId) {
        final int appId = packageState.getAppId();

        GosPackageStatePermission permission = GosPackageStatePermissions.get(callingUid, callingPid, appId, userId, false);
        if (permission == null) {
            return NONE;
        }

        maybeDeriveFlags(pmComputer, gosPs, packageState);
        return permission.filterRead(gosPs);
    }

    static boolean set(PackageManagerService pm,
                       final int callingUid, final int callingPid,
                       String packageName, int userId,
                       GosPackageState update, int editorFlags) {
        final int appId;

        synchronized (pm.mLock) {
            PackageSetting packageSetting = pm.mSettings.getPackageLPr(packageName);
            if (packageSetting == null) {
                Slogf.d(TAG, "set: no packageSetting for %s", packageName);
                return false;
            }

            appId = packageSetting.getAppId();

            // Packages with this appId use the "android.uid.system" sharedUserId, which is expensive
            // to deal with due to the large number of packages that it includes (see GosPackageState
            // doc). These packages have no need for GosPackageState.
            if (appId == Process.SYSTEM_UID) {
                Slogf.d(TAG, "set: appId of %s == SYSTEM_UID", packageName);
                return false;
            }

            GosPackageStatePermission permission = GosPackageStatePermissions.get(
                    callingUid, callingPid, appId, userId, true);

            if (permission == null) {
                Slog.d(TAG, "no write permission");
                return false;
            }

            PackageUserStateInternal userState = packageSetting.getUserStates().get(userId);
            if (userState == null) {
                Slog.d(TAG, "no user state");
                return false;
            }

            GosPackageState currentGosPs = userState.getGosPackageState();
            GosPackageState updatedGosPs = permission.filterWrite(currentGosPs, update);

            SharedUserSetting sharedUser = pm.mSettings.getSharedUserSettingLPr(packageSetting);

            if (sharedUser != null) {
                byte[] targetConfigBytes = updatedGosPs.appsScopes;
                AppsScope targetConfig = null;
                if (targetConfigBytes != null) {
                    targetConfig = AppsScope.deserialize(targetConfigBytes);
                }

                // Precompute the byte array for a peer WITH restrictSelf enabled
                AppsScope.Builder builderWithRestrictSelf = (targetConfig != null) ?
                        new AppsScope.Builder(targetConfig) :
                        new AppsScope.Builder();
                builderWithRestrictSelf.addFlag(AppsScope.FLAG_RESTRICT_SELF);
                byte[] newPeerConfigBytesWithRestrictSelf = AppsScope.serialize(builderWithRestrictSelf.build());

                // Precompute the byte array for a peer WITHOUT restrictSelf enabled
                byte[] newPeerConfigBytesWithoutRestrictSelf;
                if (targetConfig == null) {
                    newPeerConfigBytesWithoutRestrictSelf = null;
                } else {
                    AppsScope.Builder builderWithoutRestrictSelf = new AppsScope.Builder(targetConfig);
                    builderWithoutRestrictSelf.clearFlag(AppsScope.FLAG_RESTRICT_SELF);
                    newPeerConfigBytesWithoutRestrictSelf = AppsScope.serialize(builderWithoutRestrictSelf.build());
                }

                List<AndroidPackage> sharedPkgs = sharedUser.getPackages();
                for (AndroidPackage sharedPkg : sharedPkgs) {
                    PackageSetting sharedPkgSetting = pm.mSettings.getPackageLPr(sharedPkg.getPackageName());
                    if (sharedPkgSetting != null) {
                        GosPackageState psToSet;

                        if (sharedPkg.getPackageName().equals(packageName)) {
                            psToSet = updatedGosPs;
                        } else {
                            GosPackageState peerCurrentPs = sharedPkgSetting.getUserStateOrDefault(userId).getGosPackageState();

                            // Use cached AppsScope so we avoid JSON deserialization overhead inside the pm.mLock loop
                            AppsScope peerConfig = peerCurrentPs.getAppsScope();
                            boolean peerRestrictSelf = (peerConfig != null) &&
                                    ((peerConfig.flags & AppsScope.FLAG_RESTRICT_SELF) != 0);

                            byte[] newPeerConfigBytes = peerRestrictSelf ?
                                    newPeerConfigBytesWithRestrictSelf : newPeerConfigBytesWithoutRestrictSelf;

                            psToSet = new GosPackageState(
                                    updatedGosPs.flagStorage1,
                                    updatedGosPs.packageFlagStorage,
                                    updatedGosPs.storageScopes,
                                    updatedGosPs.contactScopes,
                                    newPeerConfigBytes
                            );
                        }
                        sharedPkgSetting.setGosPackageState(userId, psToSet);
                    }
                }
            } else {
                packageSetting.setGosPackageState(userId, updatedGosPs);
            }

            // will invalidate app-side caches (GosPackageState.sCache)
            pm.scheduleWritePackageRestrictions(userId);
            android.content.pm.PackageManager.invalidatePackageInfoCache();
        }

        if ((editorFlags & EDITOR_FLAG_KILL_UID_AFTER_APPLY) != 0) {
            final long token = Binder.clearCallingIdentity();
            try {
                // important to call outside the 'synchronized (pm.mLock)' section, may deadlock otherwise
                ActivityManager.getService().killUid(appId, userId, "GosPackageState");
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        if ((editorFlags & EDITOR_FLAG_NOTIFY_UID_AFTER_APPLY) != 0) {
            int uid = UserHandle.getUid(userId, appId);

            // get GosPackageState as the target app
            GosPackageState ps = getFiltered(pm, uid, GosPackageStatePermissions.UNKNOWN_CALLING_PID, packageName, userId);

            final long token = Binder.clearCallingIdentity();
            try {
                var am = LocalServices.getService(ActivityManagerInternal.class);
                am.onGosPackageStateChanged(uid, ps);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        return true;
    }

    private static void maybeDeriveFlags(Computer snapshot, GosPackageState gosPs, PackageStateInternal pkgState) {
        if ((gosPs.derivedFlags & DerivedPackageFlag.DFLAGS_SET) != 0) {
            return;
        }

        if (!gosPs.hasFlag(GosPackageStateFlag.STORAGE_SCOPES_ENABLED) && !gosPs.hasFlag(GosPackageStateFlag.CONTACT_SCOPES_ENABLED)) {
            return;
        }

        AndroidPackageInternal pkg = pkgState.getPkg();
        if (pkg == null) {
            // see AndroidPackage.pkg javadoc for an explanation
            return;
        }

        SharedUserApi sharedUser = null;
        if (pkgState.hasSharedUser()) {
            sharedUser = snapshot.getSharedUser(pkgState.getSharedUserAppId());
        }

        int flags;
        if (sharedUser != null) {
            flags = 0;
            for (AndroidPackage sharedPkg : sharedUser.getPackages()) {
                // see GosPackageState doc
                flags = deriveFlags(flags, sharedPkg);
            }
        } else {
            flags = deriveFlags(0, pkg);
        }
        gosPs.derivedFlags = flags | DerivedPackageFlag.DFLAGS_SET;
    }

    private static int deriveFlags(int flags, AndroidPackage pkg) {
        for (ParsedUsesPermission perm : pkg.getUsesPermissionMapping().values()) {
            String name = perm.getName();
            switch (name) {
                case Manifest.permission.READ_EXTERNAL_STORAGE:
                case Manifest.permission.WRITE_EXTERNAL_STORAGE: {
                    boolean writePerm = name.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    flags |= writePerm ?
                            DerivedPackageFlag.HAS_WRITE_EXTERNAL_STORAGE_DECLARATION :
                            DerivedPackageFlag.HAS_READ_EXTERNAL_STORAGE_DECLARATION;

                    int targetSdk = pkg.getTargetSdkVersion();

                    boolean legacy = targetSdk < 29
                                || (targetSdk == 29 && pkg.isRequestLegacyExternalStorage());

                    if (writePerm && legacy) {
                        // when app doesn't have "legacy external storage", WRITE_EXTERNAL_STORAGE
                        // doesn't grant write access
                        flags |= DerivedPackageFlag.EXPECTS_STORAGE_WRITE_ACCESS;
                    }

                    if ((flags & DerivedPackageFlag.EXPECTS_ALL_FILES_ACCESS) == 0) {
                        if (legacy) {
                            flags |= (DerivedPackageFlag.EXPECTS_ALL_FILES_ACCESS
                                    | DerivedPackageFlag.EXPECTS_LEGACY_EXTERNAL_STORAGE);
                        } else {
                            flags |= DerivedPackageFlag.EXPECTS_ACCESS_TO_MEDIA_FILES_ONLY;
                        }
                    }
                    continue;
                }

                case Manifest.permission.MANAGE_EXTERNAL_STORAGE:
                    flags &= ~DerivedPackageFlag.EXPECTS_ACCESS_TO_MEDIA_FILES_ONLY;
                    flags |= DerivedPackageFlag.EXPECTS_ALL_FILES_ACCESS
                            | DerivedPackageFlag.EXPECTS_STORAGE_WRITE_ACCESS
                            | DerivedPackageFlag.HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION;
                    continue;

                case Manifest.permission.MANAGE_MEDIA:
                    flags |= DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION;
                    continue;

                case Manifest.permission.ACCESS_MEDIA_LOCATION:
                    flags |= DerivedPackageFlag.HAS_ACCESS_MEDIA_LOCATION_DECLARATION;
                    continue;

                case Manifest.permission.READ_MEDIA_AUDIO:
                    flags |= DerivedPackageFlag.HAS_READ_MEDIA_AUDIO_DECLARATION;
                    continue;

                case Manifest.permission.READ_MEDIA_IMAGES:
                    flags |= DerivedPackageFlag.HAS_READ_MEDIA_IMAGES_DECLARATION;
                    continue;

                case Manifest.permission.READ_MEDIA_VIDEO:
                    flags |= DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION;
                    continue;

                case Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED:
                    flags |= DerivedPackageFlag.HAS_READ_MEDIA_VISUAL_USER_SELECTED_DECLARATION;
                    continue;

                case Manifest.permission.READ_CONTACTS:
                    flags |= DerivedPackageFlag.HAS_READ_CONTACTS_DECLARATION;
                    continue;

                case Manifest.permission.WRITE_CONTACTS:
                    flags |= DerivedPackageFlag.HAS_WRITE_CONTACTS_DECLARATION;
                    continue;

                case Manifest.permission.GET_ACCOUNTS:
                    flags |= DerivedPackageFlag.HAS_GET_ACCOUNTS_DECLARATION;
                    continue;
            }
        }

        if ((flags & DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION) != 0) {
            if ((flags & (DerivedPackageFlag.HAS_READ_EXTERNAL_STORAGE_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_AUDIO_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_IMAGES_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION
                    | DerivedPackageFlag.HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION)) == 0)
            {
                flags &= ~DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION;
            }
        }

        if ((flags & DerivedPackageFlag.HAS_MANAGE_MEDIA_DECLARATION) != 0) {
            flags |= DerivedPackageFlag.EXPECTS_STORAGE_WRITE_ACCESS;
        }

        if ((flags & DerivedPackageFlag.HAS_ACCESS_MEDIA_LOCATION_DECLARATION) != 0) {
            if ((flags & (DerivedPackageFlag.HAS_READ_EXTERNAL_STORAGE_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_IMAGES_DECLARATION
                    | DerivedPackageFlag.HAS_READ_MEDIA_VIDEO_DECLARATION
                    | DerivedPackageFlag.HAS_MANAGE_EXTERNAL_STORAGE_DECLARATION)) == 0)
            {
                flags &= ~DerivedPackageFlag.HAS_ACCESS_MEDIA_LOCATION_DECLARATION;
            }
        }

        return flags;
    }

    /** @see PackageManagerService.IPackageManagerImpl#clearApplicationUserData */
    public static void onClearApplicationUserData(PackageManagerService pm, String packageName, int userId) {
        if (packageName.equals(KnownSystemPackages.get(pm.getContext()).contactsProvider)) {
            // discard IDs that refer to entries in the contacts provider database
            clearContactScopesStorage(pm, userId);
        }
    }

    private static void clearContactScopesStorage(PackageManagerService pm, int userId) {
        for (PackageStateInternal ps : pm.snapshotComputer().getPackageStates().values()) {
            PackageUserStateInternal us = ps.getUserStateOrDefault(userId);
            GosPackageState gosPs = us.getGosPackageState();
            if (gosPs.contactScopes != null) {
                gosPs.createEditor(ps.getPackageName(), userId)
                        .setContactScopes(null)
                        .apply();
            }
        }
    }

    static int runShellCommand(PackageManagerShellCommand cmd) {
        String packageName = cmd.getNextArgRequired();
        int userId = Integer.parseInt(cmd.getNextArgRequired());

        GosPackageState.Editor ed = GosPackageState.edit(packageName, userId);
        boolean updatePermissionState = false;

        for (;;) {
            String arg = cmd.getNextArg();
            if (arg == null) {
                if (!ed.apply()) {
                    return 1;
                }
                if (updatePermissionState) {
                    cmd.mPermissionManager.updatePermissionState(packageName, userId);
                }
                return 0;
            }
            switch (arg) {
                case "add-flag", "clear-flag" ->
                    ed.setFlagState(parseFlag(cmd.getNextArgRequired()), "add-flag".equals(arg));
                case "add-package-flag", "clear-package-flag" ->
                    ed.setPackageFlagState(Integer.parseInt(cmd.getNextArgRequired()),
                            "add-package-flag".equals(arg));
                case "set-storage-scopes" ->
                    ed.setStorageScopes(getByteArrArg(cmd));
                case "set-contact-scopes" ->
                    ed.setContactScopes(getByteArrArg(cmd));
                case "set-apps-scopes-config" ->
                    ed.setAppsScopeConfig(getByteArrArg(cmd));
                case "add-apps-scopes-flag", "clear-apps-scopes-flag", "add-apps-scopes-package-allow", "add-apps-scopes-package-deny", "clear-apps-scopes-package", "dump-apps-scopes" -> {
                    byte[] data = ed.getAppsScopeConfig();
                    AppsScope config = AppsScope.deserialize(data);
                    AppsScope.Builder b = config != null ?
                            new AppsScope.Builder(config) :
                            new AppsScope.Builder();

                    String val = "dump-apps-scopes".equals(arg) ? null : cmd.getNextArgRequired();

                    switch (arg) {
                        case "add-apps-scopes-flag" -> b.addFlag(parseAppsScopeFlag(val));
                        case "clear-apps-scopes-flag" -> b.clearFlag(parseAppsScopeFlag(val));
                        case "add-apps-scopes-package-allow" -> b.addPackage(val, true);
                        case "add-apps-scopes-package-deny" -> b.addPackage(val, false);
                        case "clear-apps-scopes-package" -> b.removePackage(val);
                        case "dump-apps-scopes" -> {
                            dumpAppsScope(cmd.getOutPrintWriter(), packageName, userId, ed.getFlags(), ed.getPackageFlags(), b.build());
                            continue;
                        }
                    }
                    ed.setAppsScopeConfig(AppsScope.serialize(b.build()));
                }
                case "set-kill-uid-after-apply" ->
                    ed.setKillUidAfterApply(Boolean.parseBoolean(cmd.getNextArgRequired()));
                case "set-notify-uid-after-apply" ->
                    ed.setNotifyUidAfterApply(Boolean.parseBoolean(cmd.getNextArgRequired()));
                case "update-permission-state" ->
                    updatePermissionState = true;
                default ->
                    throw new IllegalArgumentException(arg);
            }
        }
    }

    private static void dumpAppsScope(PrintWriter pw, String packageName, int userId, long flags, long packageFlags, AppsScope config) {
        pw.println("Package: " + packageName + ", User: " + userId);
        pw.println("Flags: " + flagStorageToString(flags));
        pw.println("Package Flags: " + packageFlags);
        if (config != null) {
            pw.println("Apps Scope Config:");
            pw.println("  Flags: " + appsScopeFlagsToString(config.flags));
            if (!config.specificRules.isEmpty()) {
                pw.println("  Specific Rules:");
                for (var entry : config.specificRules.entrySet()) {
                    pw.println("    " + entry.getKey() + ": " + (entry.getValue() ? "ALLOW" : "DENY"));
                }
            }
        } else {
            pw.println("Apps Scope Config: null");
        }
    }

    private static int parseAppsScopeFlag(String s) {
        if (Character.isDigit(s.charAt(0))) {
            return Integer.parseInt(s);
        }
        try {
            return AppsScope.class.getDeclaredField(s).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String flagStorageToString(long flags) {
        StringJoiner sj = new StringJoiner(", ");
        for (Field field : GosPackageStateFlag.class.getDeclaredFields()) {
            if (field.getType() == int.class) {
                try {
                    int bit = field.getInt(null);
                    if ((flags & (1L << bit)) != 0) {
                        sj.add(field.getName());
                    }
                } catch (IllegalAccessException ignored) {}
            }
        }
        return sj.length() > 0 ? sj.toString() : "0";
    }

    private static String appsScopeFlagsToString(int flags) {
        StringJoiner sj = new StringJoiner(", ");
        for (Field field : AppsScope.class.getDeclaredFields()) {
            if (field.getName().startsWith("FLAG_") && field.getType() == int.class) {
                try {
                    int bitValue = field.getInt(null);
                    if ((flags & bitValue) != 0) {
                        sj.add(field.getName());
                    }
                } catch (IllegalAccessException ignored) {}
            }
        }
        return sj.length() > 0 ? sj.toString() : "0";
    }

    @Nullable
    private static byte[] getByteArrArg(ShellCommand cmd) {
        String s = cmd.getNextArgRequired();
        return "null".equals(s) ? null : libcore.util.HexEncoding.decode(s);
    }
}
