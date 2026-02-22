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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppsScope;
import android.os.Environment;
import android.os.FileUtils;
import android.util.AtomicFile;
import android.util.Slog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Manages storage for Apps Scope configuration in separate JSON files.
 * Location: /data/system/users/<user_id>/apps-scope/<package_name>.json
 */
class AppsScopeStorage {
    private static final String TAG = "AppsScopeStorage";
    private static final String DIR_NAME = "apps-scope";

    @Nullable
    static byte[] read(@UserIdInt int userId, String packageName) {
        File file = getFile(userId, packageName);
        try {
            return new AtomicFile(file).readFully();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read apps scope config for " + packageName + " user " + userId, e);
        }
        return null;
    }

    static void write(@UserIdInt int userId, String packageName, @Nullable byte[] data, boolean enabled) {
        File file = getFile(userId, packageName);
        File disabledFile = getDisabledFile(userId, packageName);
        if (data == null) {
            Slog.d(TAG, "write: Apps Scope config cleared for " + packageName + ", deleting storage files");
            if (file.exists()) {
                file.delete();
            }
            if (disabledFile.exists()) {
                disabledFile.delete();
            }
            return;
        }

        // Check if data is identical to avoid redundant writes
        if (enabled && file.exists()) {
            byte[] existing = read(userId, packageName);
            if (Arrays.equals(existing, data)) {
                return;
            }
        } else if (!enabled && disabledFile.exists()) {
            try {
                byte[] existing = Files.readAllBytes(disabledFile.toPath());
                if (Arrays.equals(existing, data)) {
                    return;
                }
            } catch (IOException ignored) {}
        }

        Slog.d(TAG, "write: userId=" + userId + " pkg=" + packageName + " enabled=" + enabled + " hasData=true");

        if (!enabled) {
            AppsScope config = AppsScope.deserialize(data);
            if (config == null || config.isDefault()) {
                Slog.d(TAG, "write: Apps Scope disabled with default config for " + packageName + ", deleting files");
                if (file.exists()) {
                    file.delete();
                }
                if (disabledFile.exists()) {
                    disabledFile.delete();
                }
                return;
            }

            if (file.exists()) {
                Slog.d(TAG, "write: Apps Scope disabled for " + packageName + ", renaming " + file + " to " + disabledFile);
                if (disabledFile.exists()) {
                    disabledFile.delete();
                }
                if (!file.renameTo(disabledFile)) {
                    Slog.w(TAG, "Failed to rename " + file + " to " + disabledFile);
                }
            }
            return;
        }

        File dir = file.getParentFile();
        if (!dir.exists()) {
            Slog.d(TAG, "write: directory does not exist, creating: " + dir);
            if (dir.mkdirs()) {
                Slog.d(TAG, "write: successfully created directory " + dir);
                // Set permissions: rwxrwx--x (771) and owner: system (1000)
                FileUtils.setPermissions(dir.getPath(),
                        FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH,
                        1000, 1000);
            } else {
                Slog.e(TAG, "write: FAILED to create directory " + dir);
                return;
            }
        }

        // If a disabled file exists, rename it back before writing the new data
        if (disabledFile.exists() && !file.exists()) {
            Slog.d(TAG, "write: Apps Scope re-enabled for " + packageName + ", restoring " + disabledFile + " to " + file);
            if (!disabledFile.renameTo(file)) {
                Slog.w(TAG, "Failed to restore " + disabledFile + " to " + file);
            }
        }

        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream fos = null;
        try {
            fos = atomicFile.startWrite();
            fos.write(data);
            atomicFile.finishWrite(fos);
            // Set file permissions: rw-rw---- (660) and owner: system (1000)
            FileUtils.setPermissions(file.getPath(),
                    FileUtils.S_IRUSR | FileUtils.S_IWUSR | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                    1000, 1000);
        } catch (IOException e) {
            if (fos != null) {
                atomicFile.failWrite(fos);
            }
            Slog.e(TAG, "Failed to write apps scope config for " + packageName + " user " + userId, e);
        }
    }

    private static File getFile(int userId, String packageName) {
        File userSystemDir = Environment.getUserSystemDirectory(userId);
        return new File(new File(userSystemDir, DIR_NAME), packageName + ".json");
    }

    private static File getDisabledFile(int userId, String packageName) {
        File userSystemDir = Environment.getUserSystemDirectory(userId);
        return new File(new File(userSystemDir, DIR_NAME), packageName + ".DISABLE.json");
    }
}
