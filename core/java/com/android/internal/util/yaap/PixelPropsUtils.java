/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2021 AOSP-Krypton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.internal.util.yaap;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.R;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import sun.misc.Unsafe;

/**
 * @hide
 */
public final class PixelPropsUtils {
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";
    private static final String VERSION_PREFIX = "VERSION.";
    private static final String APPCOMPAT_OVERRIDE_PREFIX = "ro.appcompat_override.";

    private static final String[] PARTITIONS = {
        "", "bootimage.", "odm.", "odm_dlkm.", "product.", "system.",
        "system_dlkm.", "system_ext.", "vendor.", "vendor_dlkm."
    };

    private static final Field OFFSET_FIELD;
    private static final Unsafe UNSAFE;

    static {
        Unsafe unsafe = null;
        Field offsetField = null;

        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
            field.setAccessible(false);

            offsetField = Field.class.getDeclaredField("offset");
            offsetField.setAccessible(true);
        } catch (Exception e) {
            Logger.e("Unable to initialize Unsafe", e);
        }

        UNSAFE = unsafe;
        OFFSET_FIELD = offsetField;
    }

    private final HashMap<String, Object> certifiedProps;

    private static final Set<String> finskyProps = Set.of(
        "FINGERPRINT",
        VERSION_PREFIX + "SECURITY_PATCH",
        VERSION_PREFIX + "DEVICE_INITIAL_SDK_INT"
    );

    private static volatile boolean sIsEnabled = false;
    private static boolean sOverridesPublished = false;

    private static PixelPropsUtils sInstance = null;

    public static PixelPropsUtils getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PixelPropsUtils.class) {
                try {
                    // see if we can even read the resource before we cache it forever
                    final String fp = context.getResources().getString(R.string.cert_fp);
                    if (fp == null || fp.isEmpty()) {
                        Logger.d("Can't read props from \"" +
                            context.getPackageName() + "\" context");
                        return null;
                    }
                } catch (Resources.NotFoundException e) {
                    Logger.d("Can't read props from \"" +
                            context.getPackageName() + "\" context");
                    return null;
                }
                sIsEnabled = true;
                sInstance = new PixelPropsUtils(context);
            }
        }
        return sInstance;
    }

    private PixelPropsUtils(Context context) {
        Resources res = context.getResources();

        // init certified props
        final String cert_device = res.getString(R.string.cert_device);
        final String cert_fp = res.getString(R.string.cert_fp);
        final String cert_model = res.getString(R.string.cert_model);
        final String cert_spl = res.getString(R.string.cert_spl);
        final String cert_manufacturer = res.getString(R.string.cert_manufacturer);
        final int cert_sdk = Integer.parseInt(res.getString(R.string.cert_sdk));

        Map<String, Object> tMap = new HashMap<>();
        String[] sections = cert_fp.split("/");
        tMap.put("ID", sections[3]);
        tMap.put("BRAND", sections[0]);
        tMap.put("MANUFACTURER", cert_manufacturer);
        tMap.put("MODEL", cert_model);
        tMap.put("PRODUCT", sections[1]);
        tMap.put("DEVICE", cert_device);
        tMap.put(VERSION_PREFIX + "RELEASE", sections[2].split(":")[1]);
        tMap.put(VERSION_PREFIX + "INCREMENTAL", sections[4].split(":")[0]);
        tMap.put(VERSION_PREFIX + "SECURITY_PATCH", cert_spl);
        tMap.put(VERSION_PREFIX + "DEVICE_INITIAL_SDK_INT", cert_sdk);
        tMap.put("FINGERPRINT", cert_fp);
        // conditionally spoofing if different
        if (Build.IS_DEBUGGABLE)
            tMap.put("IS_DEBUGGABLE", false);
        if (Build.IS_ENG)
            tMap.put("IS_ENG", false);
        if (!Build.IS_USER)
            tMap.put("IS_USER", true);
        if (!Build.TYPE.equals("user"))
            tMap.put("TYPE", "user");
        if (!Build.TAGS.equals("release-keys"))
            tMap.put("TAGS", "release-keys");
        certifiedProps = new HashMap<>(tMap);
    }

    public void setProps(String packageName) {
        if (packageName == null || !sIsEnabled) {
            return;
        }
        final String fp = (String) certifiedProps.get("FINGERPRINT");
        if (fp == null || fp.isEmpty()) {
            // no spoofing if the overlay doesn't exist
            Logger.d("Skipping setProps for \"" + packageName +
                    "\" because FINGERPRINT is empty");
            sIsEnabled = false;
            return;
        }
        Logger.d("Package = " + packageName);
        final boolean isFinsky = PACKAGE_FINSKY.equals(packageName);
        if (!isFinsky && (!PACKAGE_GMS.equals(packageName) ||
                !PROCESS_GMS_UNSTABLE.equals(Application.getProcessName()))) {
            return;
        }
        if (isFinsky) {
            certifiedProps.forEach((key, value) -> {
                if (!finskyProps.contains(key)) return; // ≣ continue
                PixelPropsUtils.setPropValue(key, value);
            });
            return;
        }
        certifiedProps.forEach(PixelPropsUtils::setPropValue);
    }

    private static void setPropValue(String key, Object value) {
        if (UNSAFE == null || OFFSET_FIELD == null) {
            Logger.e("Unsafe is unavailable", new IllegalStateException());
            return;
        }

        Field field = null;
        try {
            Logger.d("Setting prop " + key + " to " + value);

            if (key.startsWith(VERSION_PREFIX)) {
                field = Build.VERSION.class.getDeclaredField(
                        key.substring(VERSION_PREFIX.length()));
            } else {
                field = Build.class.getDeclaredField(key);
            }

            Object base = field.getDeclaringClass();
            field.setAccessible(true);
            long offset = OFFSET_FIELD.getInt(field);
            Class<?> type = field.getType();
            if (!type.isPrimitive()) {
                UNSAFE.putObject(base, offset, value);
            } else if (type == int.class && value instanceof Number) {
                UNSAFE.putInt(base, offset, ((Number) value).intValue());
            } else if (type == boolean.class && value instanceof Boolean) {
                UNSAFE.putBoolean(base, offset, (Boolean) value);
            } else {
                Logger.e("Unsupported type " + type.getName(), new IllegalStateException());
            }
        } catch (Exception e) {
            Logger.e("Failed to set prop " + key, e);
        } finally {
            if (field != null) {
                try {
                    field.setAccessible(false);
                } catch (Exception ignored) {}
            }
        }
    }

    public static boolean getIsEnabled() {
        return sIsEnabled;
    }

    /**
     * Whether zygote should turn on the appcompat sysprop overrides for a new
     * process. Only DroidGuard gets them, and only once they hold the
     * certified values.
     */
    public static boolean shouldOverrideSysprops(Context context, String processName) {
        if (!PROCESS_GMS_UNSTABLE.equals(processName)) {
            return false;
        }
        synchronized (PixelPropsUtils.class) {
            if (!sOverridesPublished) {
                sOverridesPublished = publishAppcompatOverrides(context);
            }
        }
        return sOverridesPublished;
    }

    private static boolean publishAppcompatOverrides(Context context) {
        final PixelPropsUtils ppu = getInstance(context);
        if (ppu == null || !sIsEnabled) {
            return false;
        }
        final Map<String, String> overrides = ppu.nativeProps();
        int published = 0;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            final String name = entry.getKey();
            final String value = entry.getValue();
            final String real = SystemProperties.get(name, "");
            if (value == null || value.isEmpty() || real.isEmpty() || real.equals(value)) {
                continue;
            }
            final String overrideName = APPCOMPAT_OVERRIDE_PREFIX + name;
            final String current = SystemProperties.get(overrideName, "");
            if (current.equals(value)) {
                published++;
                continue;
            }
            if (!current.isEmpty()) {
                Logger.e("Override for " + name + " already holds another value",
                        new IllegalStateException());
                continue;
            }
            try {
                SystemProperties.set(overrideName, value);
                published++;
            } catch (Exception e) {
                Logger.e("Failed to override " + name, e);
            }
        }
        Logger.d("Published " + published + " appcompat overrides");
        return published > 0;
    }

    private Map<String, String> nativeProps() {
        final Map<String, String> props = new LinkedHashMap<>();
        final String fp = (String) certifiedProps.get("FINGERPRINT");
        final String id = (String) certifiedProps.get("ID");
        final String release = (String) certifiedProps.get(VERSION_PREFIX + "RELEASE");
        final String incremental = (String) certifiedProps.get(VERSION_PREFIX + "INCREMENTAL");
        final String product = (String) certifiedProps.get("PRODUCT");

        for (String partition : PARTITIONS) {
            final String build = "ro." + partition + "build.";
            props.put(build + "fingerprint", fp);
            props.put(build + "id", id);
            props.put(build + "version.release", release);
            props.put(build + "version.release_or_codename", release);
            props.put(build + "version.incremental", incremental);
            props.put(build + "type", "user");
            props.put(build + "tags", "release-keys");

            final String productPrefix = "ro.product." + partition;
            props.put(productPrefix + "brand", (String) certifiedProps.get("BRAND"));
            props.put(productPrefix + "manufacturer",
                    (String) certifiedProps.get("MANUFACTURER"));
            props.put(productPrefix + "model", (String) certifiedProps.get("MODEL"));
            props.put(productPrefix + "device", (String) certifiedProps.get("DEVICE"));
            props.put(productPrefix + "name", product);
        }

        final String spl = (String) certifiedProps.get(VERSION_PREFIX + "SECURITY_PATCH");
        props.put("ro.build.version.security_patch", spl);
        props.put("ro.vendor.build.security_patch", spl);
        props.put("ro.product.first_api_level",
                String.valueOf(certifiedProps.get(VERSION_PREFIX + "DEVICE_INITIAL_SDK_INT")));
        props.put("ro.build.description", product + "-user " + release + " " + id + " "
                + incremental + " release-keys");
        props.put("ro.build.flavor", product + "-user");
        props.put("ro.debuggable", "0");
        return props;
    }

    private static class Logger {
        private static final String TAG = "PixelPropsUtils";

        private static void e(String msg, Exception e) {
            Log.e(TAG, msg, e);
        }

        private static void d(String msg) {
            if (!isLoggable()) return;
            Log.d(TAG, msg);
        }

        private static boolean isLoggable() {
            return Log.isLoggable(TAG, Log.DEBUG);
        }
    }
}
