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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.Slog;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * Configuration for Apps Scope, which controls the visibility of other apps.
 * @hide
 */
public final class AppsScope implements Parcelable {
    private static final String TAG = "AppsScope";

    public static final int FLAG_RESTRICT_SELF = 1;
    public static final int FLAG_RESTRICT_SHARED_CERT = 1 << 1;
    public static final int FLAG_RESTRICT_SYSTEM = 1 << 2;
    public static final int FLAG_RESTRICT_QUERIES = 1 << 3;

    public final int flags;
    @NonNull
    public final Map<String, Boolean> specificRules;

    public AppsScope(int flags, @NonNull Map<String, Boolean> specificRules) {
        this.flags = flags;
        ArrayMap<String, Boolean> map = new ArrayMap<>(specificRules.size());
        map.putAll(specificRules);
        this.specificRules = Collections.unmodifiableMap(map);
    }

    public boolean isDefault() {
        return flags == 0 && specificRules.isEmpty();
    }

    private AppsScope(Parcel in) {
        flags = in.readInt();
        int size = in.readInt();
        ArrayMap<String, Boolean> map = new ArrayMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(in.readString(), in.readBoolean());
        }
        specificRules = Collections.unmodifiableMap(map);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int parcelFlags) {
        dest.writeInt(this.flags);
        dest.writeInt(specificRules.size());
        for (Map.Entry<String, Boolean> entry : specificRules.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeBoolean(entry.getValue());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<AppsScope> CREATOR = new Creator<AppsScope>() {
        @Override
        public AppsScope createFromParcel(Parcel in) {
            return new AppsScope(in);
        }

        @Override
        public AppsScope[] newArray(int size) {
            return new AppsScope[size];
        }
    };

    private static final int VERSION = 1;

    @NonNull
    public static android.content.Intent createConfigActivityIntent(@NonNull String packageName) {
        android.content.Intent intent = new android.content.Intent("android.settings.APPS_SCOPE_DETAILS");
        intent.setData(android.net.Uri.parse("package:" + packageName));
        return intent;
    }

    @Nullable
    public static byte[] serialize(@Nullable AppsScope config) {
        if (config == null) {
            return null;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("restrictSelf", (config.flags & FLAG_RESTRICT_SELF) != 0);
            json.put("restrictSharedCert", (config.flags & FLAG_RESTRICT_SHARED_CERT) != 0);
            json.put("restrictSystem", (config.flags & FLAG_RESTRICT_SYSTEM) != 0);
            json.put("restrictQueries", (config.flags & FLAG_RESTRICT_QUERIES) != 0);

            JSONObject specificRules = new JSONObject();
            for (Map.Entry<String, Boolean> entry : config.specificRules.entrySet()) {
                specificRules.put(entry.getKey(), entry.getValue());
            }
            json.put("specificRules", specificRules);

            return json.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            Slog.e(TAG, "serialization failed", e);
            return null;
        }
    }

    @Nullable
    public static AppsScope deserialize(@Nullable byte[] data) {
        if (data == null) {
            return null;
        }

        try {
            String str = new String(data, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(str);

            int flags = 0;
            if (json.optBoolean("restrictSelf", false)) flags |= FLAG_RESTRICT_SELF;
            if (json.optBoolean("restrictSharedCert", false)) flags |= FLAG_RESTRICT_SHARED_CERT;
            if (json.optBoolean("restrictSystem", false)) flags |= FLAG_RESTRICT_SYSTEM;
            if (json.optBoolean("restrictQueries", false)) flags |= FLAG_RESTRICT_QUERIES;

            ArrayMap<String, Boolean> specificRules = new ArrayMap<>();
            JSONObject rulesObj = json.optJSONObject("specificRules");
            if (rulesObj != null) {
                Iterator<String> keys = rulesObj.keys();
                while (keys.hasNext()) {
                    String pkg = keys.next();
                    specificRules.put(pkg, rulesObj.optBoolean(pkg, true));
                }
            }

            return new AppsScope(flags, specificRules);
        } catch (JSONException e) {
            Slog.e(TAG, "deserialization failed", e);
            return null;
        }
    }

    /** @hide */
    public static class Builder {
        private int flags;
        private final ArrayMap<String, Boolean> specificRules;

        public Builder() {
            this.flags = 0;
            this.specificRules = new ArrayMap<>();
        }

        public Builder(AppsScope config) {
            this.flags = config.flags;
            this.specificRules = new ArrayMap<>();
            this.specificRules.putAll(config.specificRules);
        }

        @NonNull
        public static Builder from(@Nullable AppsScope config) {
            return config != null ? new Builder(config) : new Builder();
        }

        public Builder setFlags(int flags) {
            this.flags = flags;
            return this;
        }

        public Builder addFlag(int flag) {
            this.flags |= flag;
            return this;
        }

        public Builder clearFlag(int flag) {
            this.flags &= ~flag;
            return this;
        }

        public Builder addPackage(String pkg, boolean allowed) {
            specificRules.put(pkg, allowed);
            return this;
        }

        public Builder removePackage(String pkg) {
            specificRules.remove(pkg);
            return this;
        }

        public AppsScope build() {
            return new AppsScope(flags, specificRules);
        }
    }
}
