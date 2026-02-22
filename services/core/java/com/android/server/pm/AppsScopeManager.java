/*
 * Copyright (C) 2026 GrapheneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;

import android.app.AppsScope;
import android.content.pm.GosPackageState;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;

import com.android.server.pm.pkg.PackageStateInternal;

import java.util.Map;
import java.util.function.IntFunction;

/**
 * Manages visibility rules defined by Apps Scope (GrapheneOS).
 *
 * Reads configuration from in-memory {@link GosPackageState} (loaded at boot),
 * avoiding disk I/O on the hot path.
 *
 * Returns a tri-state action:
 *   ACTION_ALLOW (0) = Force visible, return false in shouldFilterApplication.
 *   ACTION_DENY  (1) = Force hidden, return true in shouldFilterApplication.
 *   ACTION_PASS  (2) = No decision, continue with default Android logic.
 */
public class AppsScopeManager {
    private static final String TAG = "AppsScopeManager";

    /** Force visible: the app must be shown. */
    public static final int ACTION_ALLOW = 0;
    /** Force hidden: the app must be hidden. */
    public static final int ACTION_DENY  = 1;
    /** No decision: let the normal Android filtering logic decide. */
    public static final int ACTION_PASS  = 2;

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Main entry point called from ComputerEngine.shouldFilterApplication.
     * Resolves calling UID to package states, determines relationship flags,
     * and evaluates Apps Scope visibility rules using two specialized passes.
     *
     * @param ps               Target package state.
     * @param callingUid       The raw calling UID.
     * @param settingsResolver Callback to resolve an appId to its SettingBase.
     * @return ACTION_ALLOW, ACTION_DENY, or ACTION_PASS.
     */
    public static int checkVisibility(PackageStateInternal ps, int callingUid,
                                      IntFunction<SettingBase> settingsResolver) {
        if (ps == null) return ACTION_PASS;

        // --- Step 1: Resolve callingUid to PackageStateInternal(s) ---
        final int callingAppId = UserHandle.getAppId(callingUid);
        final SettingBase callingSetting = settingsResolver.apply(callingAppId);
        if (callingSetting == null) return ACTION_PASS;

        PackageStateInternal[] callingPkgStates = null;
        SigningDetails callingSigning = null;

        if (callingSetting instanceof SharedUserSetting) {
            ArraySet<? extends PackageStateInternal> pkgs =
                    ((SharedUserSetting) callingSetting).getPackageStates();
            callingPkgStates = new PackageStateInternal[pkgs.size()];
            for (int i = 0; i < pkgs.size(); i++) {
                callingPkgStates[i] = pkgs.valueAt(i);
            }
            callingSigning = ((SharedUserSetting) callingSetting).getSigningDetails();
        } else if (callingSetting instanceof PackageSetting) {
            callingPkgStates = new PackageStateInternal[] { (PackageSetting) callingSetting };
            callingSigning = ((PackageSetting) callingSetting).getSigningDetails();
        }

        if (callingPkgStates == null) return ACTION_PASS;

        // --- Determine relationship flags ---
        final String targetPackageName = ps.getPackageName();
        final int N = callingPkgStates.length;
        final int userId = UserHandle.getUserId(callingUid);
        AppsScope[] loadedConfigs = new AppsScope[N];

        // --- Pass 1: Self-Check (restrictSelf only) ---
        // If the target is within the calling UID group, its own restrictSelf
        // takes absolute priority over any peer rules.
        for (int i = 0; i < N; i++) {
            AppsScope config = getAppsScope(callingPkgStates[i], userId);
            loadedConfigs[i] = config;

            if (config == null) continue;

            if (callingPkgStates[i].getPackageName().equals(targetPackageName)) {
                int action = checkSelfRestriction(config);
                if (action == ACTION_ALLOW || action == ACTION_DENY) {
                    return action;
                }
            }
        }

        // --- Pass 2: Peer Rules (Specific Rules + Category Restrictions) ---
        final boolean isSystem = ps.isSystem();
        // TODO: isCore implementation to isolate core system apps
        // final boolean isCore = (ps.getAppId() < Process.FIRST_APPLICATION_UID);
        boolean isSharedCert = false;
        if (callingSigning != null && ps.getSigningDetails() != null) {
            isSharedCert = (compareSignatures(callingSigning, ps.getSigningDetails())
                    == PackageManager.SIGNATURE_MATCH);
        }

        for (int i = 0; i < N; i++) {
            AppsScope config = loadedConfigs[i];
            if (config == null) continue;

            int action = checkPeerRestriction(config, targetPackageName,
                    isSystem, isSharedCert);
            if (action == ACTION_DENY) {
                return ACTION_DENY;
            }
        }

        return ACTION_PASS;
    }

    // =========================================================================
    // Helper: In-memory config reader
    // =========================================================================

    /**
     * Extracts the Apps Scope config from in-memory {@link GosPackageState}.
     * No disk I/O - data was loaded at boot by {@link GosPackageStatePersistence}.
     * Uses cached AppsScope object to avoid redundant deserialization.
     *
     * @return Parsed AppsScope, or null if no config exists.
     */
    private static AppsScope getAppsScope(PackageStateInternal pkgState, int userId) {
        try {
            GosPackageState gosState = pkgState.getUserStateOrDefault(userId).getGosPackageState();
            if (gosState == null) return null;
            return gosState.getAppsScope();
        } catch (Exception e) {
            Log.e(TAG, "Error reading AppsScope for " + pkgState.getPackageName()
                    + " user " + userId, e);
            return null;
        }
    }

    // =========================================================================
    // Pass 1: Self-Restriction (restrictSelf only)
    // =========================================================================

    /**
     * Checks ONLY the restrictSelf flag for a package viewing itself.
     * Called exclusively when callingPkg == targetPkg.
     */
    private static int checkSelfRestriction(AppsScope config) {
        boolean restrictSelf = (config.flags & AppsScope.FLAG_RESTRICT_SELF) != 0;
        if (!restrictSelf) {
            return ACTION_ALLOW;
        }
        return ACTION_DENY;
    }

    // =========================================================================
    // Pass 2: Peer Restrictions (Specific Rules + Category Wildcards)
    // =========================================================================

    /**
     * Checks specific rules and category restrictions for a peer package.
     * Does NOT check restrictSelf (already handled in Pass 1).
     *
     * Priority: Specific Rules > SharedCert > System > Queries
     */
    private static int checkPeerRestriction(AppsScope config, String targetPkg,
                                            boolean isSystem, boolean isSharedCert) {
        // --- Specific Rules ---
        Map<String, Boolean> specificRules = config.specificRules;
        if (specificRules != null && specificRules.containsKey(targetPkg)) {
            Boolean allowed = specificRules.get(targetPkg);
            if (allowed == null || !allowed) {
                return ACTION_DENY;
            }
            return ACTION_PASS;
        }

        // --- Category Restrictions: SharedCert > System > Queries ---
        boolean restricted;
        if (isSharedCert) {
            restricted = (config.flags & AppsScope.FLAG_RESTRICT_SHARED_CERT) != 0;
        } else if (isSystem) {
            restricted = (config.flags & AppsScope.FLAG_RESTRICT_SYSTEM) != 0;
        } else {
            restricted = (config.flags & AppsScope.FLAG_RESTRICT_QUERIES) != 0;
        }

        if (restricted) {
            return ACTION_DENY;
        }

        return ACTION_PASS;
    }

}

