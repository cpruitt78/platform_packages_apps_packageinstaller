/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.packageinstaller.wear;

import android.annotation.Nullable;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.packageinstaller.DeviceUtils;
import com.android.packageinstaller.PackageUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service that will install/uninstall packages. It will check for permissions and features as well.
 *
 * -----------
 *
 * Debugging information:
 *
 *  Install Action example:
 *  adb shell am startservice -a com.android.packageinstaller.wear.INSTALL_PACKAGE \
 *     -t vnd.android.cursor.item/wearable_apk \
 *     -d content://com.google.android.clockwork.home.provider/host/com.google.android.wearable.app/wearable/com.google.android.gms/apk \
 *     --es android.intent.extra.INSTALLER_PACKAGE_NAME com.google.android.gms \
 *     --ez com.google.android.clockwork.EXTRA_CHECK_PERMS false \
 *     --eu com.google.android.clockwork.EXTRA_PERM_URI content://com.google.android.clockwork.home.provider/host/com.google.android.wearable.app/permissions \
 *     com.android.packageinstaller/com.android.packageinstaller.wear.WearPackageInstallerService
 *
 *  Retry GMS:
 *  adb shell am startservice -a com.android.packageinstaller.wear.RETRY_GMS \
 *     com.android.packageinstaller/com.android.packageinstaller.wear.WearPackageInstallerService
 */
public class WearPackageInstallerService extends Service {
    private static final String TAG = "WearPkgInstallerService";

    private static final String KEY_PERM_URI =
            "com.google.android.clockwork.EXTRA_PERM_URI";
    private static final String KEY_CHECK_PERMS =
            "com.google.android.clockwork.EXTRA_CHECK_PERMS";
    private static final String KEY_SKIP_IF_SAME_VERSION =
            "com.google.android.clockwork.EXTRA_SKIP_IF_SAME_VERSION";
    private static final String KEY_COMPRESSION_ALG =
            "com.google.android.clockwork.EXTRA_KEY_COMPRESSION_ALG";
    private static final String KEY_COMPANION_SDK_VERSION =
            "com.google.android.clockwork.EXTRA_KEY_COMPANION_SDK_VERSION";
    private static final String KEY_COMPANION_DEVICE_VERSION =
            "com.google.android.clockwork.EXTRA_KEY_COMPANION_DEVICE_VERSION";

    private static final String KEY_PACKAGE_NAME =
            "com.google.android.clockwork.EXTRA_PACKAGE_NAME";
    private static final String KEY_APP_LABEL = "com.google.android.clockwork.EXTRA_APP_LABEL";
    private static final String KEY_APP_ICON_URI =
            "com.google.android.clockwork.EXTRA_APP_ICON_URI";
    private static final String KEY_PERMS_LIST = "com.google.android.clockwork.EXTRA_PERMS_LIST";
    private static final String KEY_HAS_LAUNCHER =
            "com.google.android.clockwork.EXTRA_HAS_LAUNCHER";

    private static final String HOME_APP_PACKAGE_NAME = "com.google.android.wearable.app";
    private static final String SHOW_PERMS_SERVICE_CLASS =
            "com.google.android.clockwork.packagemanager.ShowPermsService";

    private static final String ASSET_URI_ARG = "assetUri";
    private static final String PACKAGE_NAME_ARG = "packageName";
    private static final String PERM_URI_ARG = "permUri";
    private static final String START_ID_ARG = "startId";
    private static final String CHECK_PERMS_ARG = "checkPerms";
    private static final String SKIP_IF_SAME_VERSION_ARG = "skipIfSameVersion";
    private static final String COMPRESSION_ALG = "compressionAlg";
    private static final String COMPANION_SDK_VERSION = "companionSdkVersion";
    private static final String COMPANION_DEVICE_VERSION = "companionDeviceVersion";

    /**
     * Normally sent by the Play store (See http://go/playstore-gms_updated), we instead
     * broadcast, ourselves. http://b/17387718
     */
    private static final String GMS_UPDATED_BROADCAST = "com.google.android.gms.GMS_UPDATED";
    public static final String GMS_PACKAGE_NAME = "com.google.android.gms";

    private final int START_INSTALL = 1;
    private final int START_UNINSTALL = 2;

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case START_INSTALL:
                    installPackage(msg.getData().getString(PACKAGE_NAME_ARG),
                            (Uri) msg.getData().getParcelable(ASSET_URI_ARG),
                            (Uri) msg.getData().getParcelable(PERM_URI_ARG),
                            msg.getData().getInt(START_ID_ARG),
                            msg.getData().getBoolean(CHECK_PERMS_ARG),
                            msg.getData().getBoolean(SKIP_IF_SAME_VERSION_ARG),
                            msg.getData().getString(COMPRESSION_ALG),
                            msg.getData().getInt(COMPANION_SDK_VERSION),
                            msg.getData().getInt(COMPANION_DEVICE_VERSION));
                    break;
                case START_UNINSTALL:
                    uninstallPackage(msg.getData().getString(PACKAGE_NAME_ARG),
                            msg.getData().getInt(START_ID_ARG));
                    break;
            }
        }
    }
    private ServiceHandler mServiceHandler;

    private static volatile PowerManager.WakeLock lockStatic = null;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        HandlerThread thread = new HandlerThread("PackageInstallerThread",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        mServiceHandler = new ServiceHandler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!DeviceUtils.isWear(this)) {
            Log.w(TAG, "Not running on wearable");
            return START_NOT_STICKY;
        }
        PowerManager.WakeLock lock = getLock(this.getApplicationContext());
        if (!lock.isHeld()) {
            lock.acquire();
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Got install/uninstall request " + intent);
        }
        if (intent != null) {
            if (Intent.ACTION_INSTALL_PACKAGE.equals(intent.getAction())) {
                final Message msg = mServiceHandler.obtainMessage(START_INSTALL);
                final Bundle startInstallArgs = new Bundle();
                startInstallArgs.putParcelable(ASSET_URI_ARG, intent.getData());
                startInstallArgs.putString(PACKAGE_NAME_ARG, intent.getStringExtra(
                        Intent.EXTRA_INSTALLER_PACKAGE_NAME));
                startInstallArgs.putInt(START_ID_ARG, startId);
                Uri permUri = intent.getParcelableExtra(KEY_PERM_URI);
                startInstallArgs.putParcelable(PERM_URI_ARG, permUri);
                startInstallArgs.putBoolean(CHECK_PERMS_ARG,
                        intent.getBooleanExtra(KEY_CHECK_PERMS, true));
                startInstallArgs.putBoolean(SKIP_IF_SAME_VERSION_ARG,
                        intent.getBooleanExtra(KEY_SKIP_IF_SAME_VERSION, false));
                startInstallArgs.putString(COMPRESSION_ALG,
                        intent.getStringExtra(KEY_COMPRESSION_ALG));
                startInstallArgs.putInt(COMPANION_SDK_VERSION,
                        intent.getIntExtra(KEY_COMPANION_SDK_VERSION, 0));
                startInstallArgs.putInt(COMPANION_DEVICE_VERSION,
                        intent.getIntExtra(KEY_COMPANION_DEVICE_VERSION, 0));
                msg.setData(startInstallArgs);
                mServiceHandler.sendMessage(msg);
            } else if (Intent.ACTION_UNINSTALL_PACKAGE.equals(intent.getAction())) {
                Message msg = mServiceHandler.obtainMessage(START_UNINSTALL);
                Bundle startUninstallArgs = new Bundle();
                startUninstallArgs.putString(PACKAGE_NAME_ARG, intent.getStringExtra(
                        Intent.EXTRA_INSTALLER_PACKAGE_NAME));
                startUninstallArgs.putInt(START_ID_ARG, startId);
                msg.setData(startUninstallArgs);
                mServiceHandler.sendMessage(msg);
            }
        }
        return START_NOT_STICKY;
    }

    private void installPackage(String packageName, Uri packageUri, Uri permUri, int startId,
            boolean checkPerms, boolean skipIfSameVersion, @Nullable String compressionAlg,
            int companionSdkVersion, int companionDeviceVersion) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Installing package: " + packageName + ", packageUri: " + packageUri +
                    ",permUri: " + permUri + ", startId: " + startId + ", checkPerms: " +
                    checkPerms + ", skipIfSameVersion: " + skipIfSameVersion +
                    ", compressionAlg: " + compressionAlg + ", companionSdkVersion: " +
                    companionSdkVersion + ", companionDeviceVersion: " + companionDeviceVersion);
        }
        final PackageManager pm = getPackageManager();
        File tempFile = null;
        int installFlags = 0;
        PowerManager.WakeLock lock = getLock(this.getApplicationContext());
        boolean messageSent = false;
        try {
            PackageInfo existingPkgInfo = null;
            try {
                existingPkgInfo = pm.getPackageInfo(packageName,
                        PackageManager.GET_UNINSTALLED_PACKAGES);
                if(existingPkgInfo != null) {
                    installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Ignore this exception. We could not find the package, will treat as a new
                // installation.
            }
            if((installFlags & PackageManager.INSTALL_REPLACE_EXISTING )!= 0) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Replacing package:" + packageName);
                }
            }
            ParcelFileDescriptor parcelFd = getContentResolver()
                    .openFileDescriptor(packageUri, "r");
            tempFile = WearPackageUtil.getFileFromFd(WearPackageInstallerService.this,
                    parcelFd, packageName, compressionAlg);
            if (tempFile == null) {
                Log.e(TAG, "Could not create a temp file from FD for " + packageName);
                return;
            }
            PackageParser.Package pkg = PackageUtil.getPackageInfo(tempFile);
            if (pkg == null) {
                Log.e(TAG, "Could not parse apk information for " + packageName);
                return;
            }

            if (!pkg.packageName.equals(packageName)) {
                Log.e(TAG, "Wearable Package Name has to match what is provided for " +
                        packageName);
                return;
            }

            List<String> wearablePerms = pkg.requestedPermissions;

            // Log if the installed pkg has a higher version number.
            if (existingPkgInfo != null) {
                if (existingPkgInfo.versionCode == pkg.mVersionCode) {
                    if (skipIfSameVersion) {
                        Log.w(TAG, "Version number (" + pkg.mVersionCode +
                                ") of new app is equal to existing app for " + packageName +
                                "; not installing due to versionCheck");
                        return;
                    } else {
                        Log.w(TAG, "Version number of new app (" + pkg.mVersionCode +
                                ") is equal to existing app for " + packageName);
                    }
                } else if (existingPkgInfo.versionCode > pkg.mVersionCode) {
                    Log.w(TAG, "Version number of new app (" + pkg.mVersionCode +
                            ") is lower than existing app ( " + existingPkgInfo.versionCode +
                            ") for " + packageName);
                }

                // Following the Android Phone model, we should only check for permissions for any
                // newly defined perms.
                if (existingPkgInfo.requestedPermissions != null) {
                    for (int i = 0; i < existingPkgInfo.requestedPermissions.length; ++i) {
                        // If the permission is granted, then we will not ask to request it again.
                        if ((existingPkgInfo.requestedPermissionsFlags[i] &
                                PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                            wearablePerms.remove(existingPkgInfo.requestedPermissions[i]);
                        }
                    }
                }
            }

            // Check permissions on both the new wearable package and also on the already installed
            // wearable package.
            // If the app is targeting API level 23, we will also start a service in ClockworkHome
            // which will ultimately prompt the user to accept/reject permissions.
            if (checkPerms && !checkPermissions(pkg, companionSdkVersion, companionDeviceVersion,
                    permUri, wearablePerms, tempFile)) {
                Log.w(TAG, "Wearable does not have enough permissions.");
                return;
            }

            // Check that the wearable has all the features.
            boolean hasAllFeatures = true;
            if (pkg.reqFeatures != null) {
                for (FeatureInfo feature : pkg.reqFeatures) {
                    if (feature.name != null && !pm.hasSystemFeature(feature.name) &&
                            (feature.flags & FeatureInfo.FLAG_REQUIRED) != 0) {
                        Log.e(TAG, "Wearable does not have required feature: " + feature +
                                " for " + packageName);
                        hasAllFeatures = false;
                    }
                }
            }

            if (!hasAllFeatures) {
                return;
            }

            // Finally install the package.
            pm.installPackage(Uri.fromFile(tempFile),
                    new PackageInstallObserver(this, lock, startId), installFlags, packageName);
            messageSent = true;
            Log.i(TAG, "Sent installation request for " + packageName);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Could not find the file with URI " + packageUri, e);
        } finally {
            if (!messageSent) {
                // Some error happened. If the message has been sent, we can wait for the observer
                // which will finish the service.
                if (tempFile != null) {
                    tempFile.delete();
                }
                finishService(lock, startId);
            }
        }
    }

    private void uninstallPackage(String packageName, int startId) {
        final PackageManager pm = getPackageManager();
        PowerManager.WakeLock lock = getLock(this.getApplicationContext());
        pm.deletePackage(packageName, new PackageDeleteObserver(lock, startId),
                PackageManager.DELETE_ALL_USERS);
        startPermsServiceForUninstall(packageName);
        Log.i(TAG, "Sent delete request for " + packageName);
    }

    private boolean checkPermissions(PackageParser.Package pkg, int companionSdkVersion,
            int companionDeviceVersion, Uri permUri, List<String> wearablePermissions,
            File apkFile) {
        if (permUri == null) {
            Log.e(TAG, "Permission URI is null");
            return false;
        }
        Cursor permCursor = getContentResolver().query(permUri, null, null, null, null);
        if (permCursor == null) {
            Log.e(TAG, "Could not get the cursor for the permissions");
            return false;
        }

        final String packageName = pkg.packageName;

        Set<String> grantedPerms = new HashSet<>();
        Set<String> ungrantedPerms = new HashSet<>();
        while(permCursor.moveToNext()) {
            // Make sure that the MatrixCursor returned by the ContentProvider has 2 columns and
            // verify their types.
            if (permCursor.getColumnCount() == 2
                    && Cursor.FIELD_TYPE_STRING == permCursor.getType(0)
                    && Cursor.FIELD_TYPE_INTEGER == permCursor.getType(1)) {
                String perm = permCursor.getString(0);
                Integer granted = permCursor.getInt(1);
                if (granted == 1) {
                    grantedPerms.add(perm);
                } else {
                    ungrantedPerms.add(perm);
                }
            }
        }
        permCursor.close();

        ArrayList<String> unavailableWearablePerms = new ArrayList<>();
        for (String wearablePerm : wearablePermissions) {
            if (!grantedPerms.contains(wearablePerm)) {
                unavailableWearablePerms.add(wearablePerm);
                if (!ungrantedPerms.contains(wearablePerm)) {
                    // This is an error condition. This means that the wearable has permissions that
                    // are not even declared in its host app. This is a developer error.
                    Log.e(TAG, "Wearable " + packageName + " has a permission \"" + wearablePerm
                            + "\" that is not defined in the host application's manifest.");
                } else {
                    Log.w(TAG, "Wearable " + packageName + " has a permission \"" + wearablePerm +
                            "\" that is not granted in the host application.");
                }
            }
        }


        // If the Wear App is targeted for M-release, since the permission model has been changed,
        // permissions may not be granted on the phone yet. We need a different flow for user to
        // accept these permissions.
        //
        // Case 1: Companion App >= 23 (and running on M), Wear App targeting >= 23
        //    - If Wear is running L (ie DMR1), show a dialog so that the user can accept all perms
        //    - If Wear is running M (ie E-release), use new permission model.
        // Case 2: Companion App <= 22, Wear App targeting <= 22
        //    - Default to old behavior.
        // Case 3: Companion App <= 22, Wear App targeting >= 23
        //    - If Wear is running L (ie DMR1), install the app as before. In effect, pretend
        //      like wear app is targeting 22.
        //    - If Wear is running M (ie E-release), use new permission model.
        // Case 4: Companion App >= 23 (and running on M), Wear App targeting <= 22
        //    - Show a warning below to the developer.
        //    - Show a dialog as in Case 1 with DMR1. This behavior will happen in E and DMR1.
        // Case 5: We did not get Companion App's/Device's version (we have to guess here)
        //    - Show dialog if Wear App targeting >= 23 and Wear is not running M
        if (unavailableWearablePerms.size() > 0) {
            boolean isCompanionTargetingM = companionSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1;
            boolean isCompanionRunningM = companionDeviceVersion > Build.VERSION_CODES.LOLLIPOP_MR1;
            boolean isWearTargetingM =
                    pkg.applicationInfo.targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1;
            boolean isWearRunningM = Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1;

            if (companionSdkVersion == 0 || companionDeviceVersion == 0) { // Case 5
                if (isWearTargetingM && !isWearRunningM) {
                    startPermsServiceForInstall(pkg, apkFile, unavailableWearablePerms);
                }
            } else if (isCompanionTargetingM && isCompanionRunningM) {
                if (!isWearTargetingM) {  // Case 4
                    Log.w(TAG, "MNC: Wear app's targetSdkVersion should be at least 23, if phone " +
                            "app is targeting at least 23.");
                    startPermsServiceForInstall(pkg, apkFile, unavailableWearablePerms);
                } else if (!isWearRunningM) {  // Case 1, part 1
                    startPermsServiceForInstall(pkg, apkFile, unavailableWearablePerms);
                }
            }  // Else, nothing to do. See explanation above.
        }

        return unavailableWearablePerms.size() == 0;
    }

    private void finishService(PowerManager.WakeLock lock, int startId) {
        if (lock.isHeld()) {
            lock.release();
        }
        stopSelf(startId);
    }

    private synchronized PowerManager.WakeLock getLock(Context context) {
        if (lockStatic == null) {
            PowerManager mgr =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            lockStatic = mgr.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, context.getClass().getSimpleName());
            lockStatic.setReferenceCounted(true);
        }
        return lockStatic;
    }

    private void startPermsServiceForInstall(final PackageParser.Package pkg, final File apkFile,
            ArrayList<String> unavailableWearablePerms) {
        final String packageName = pkg.packageName;

        Intent showPermsIntent = new Intent()
                .setComponent(new ComponentName(HOME_APP_PACKAGE_NAME, SHOW_PERMS_SERVICE_CLASS))
                .setAction(Intent.ACTION_INSTALL_PACKAGE);
        final PackageManager pm = getPackageManager();
        pkg.applicationInfo.publicSourceDir = apkFile.getPath();
        final CharSequence label = pkg.applicationInfo.loadLabel(pm);
        final Uri iconUri = getIconFileUri(packageName, pkg.applicationInfo.loadIcon(pm));
        if (TextUtils.isEmpty(label) || iconUri == null) {
            Log.e(TAG, "MNC: Could not launch service since either label " + label +
                    ", or icon Uri " + iconUri + " is invalid.");
        } else {
            showPermsIntent.putExtra(KEY_APP_LABEL, label);
            showPermsIntent.putExtra(KEY_APP_ICON_URI, iconUri);
            showPermsIntent.putExtra(KEY_PACKAGE_NAME, packageName);
            showPermsIntent.putExtra(KEY_PERMS_LIST,
                    unavailableWearablePerms.toArray(new String[0]));
            showPermsIntent.putExtra(KEY_HAS_LAUNCHER, WearPackageUtil.hasLauncherActivity(pkg));

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "MNC: Launching Intent " + showPermsIntent + " for " + packageName +
                        " with name " + label);
            }
            startService(showPermsIntent);
        }
    }

    private void startPermsServiceForUninstall(final String packageName) {
        Intent showPermsIntent = new Intent()
                .setComponent(new ComponentName(HOME_APP_PACKAGE_NAME, SHOW_PERMS_SERVICE_CLASS))
                .setAction(Intent.ACTION_UNINSTALL_PACKAGE);
        showPermsIntent.putExtra(KEY_PACKAGE_NAME, packageName);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Launching Intent " + showPermsIntent + " for " + packageName);
        }
        startService(showPermsIntent);
    }

    private Uri getIconFileUri(final String packageName, final Drawable d) {
        if (d == null || !(d instanceof BitmapDrawable)) {
            Log.e(TAG, "Drawable is not a BitmapDrawable for " + packageName);
            return null;
        }
        File iconFile = WearPackageUtil.getIconFile(this, packageName);

        if (iconFile == null) {
            Log.e(TAG, "Could not get icon file for " + packageName);
            return null;
        }

        FileOutputStream fos = null;
        try {
            // Convert bitmap to byte array
            Bitmap bitmap = ((BitmapDrawable) d).getBitmap();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 0, bos);

            // Write the bytes into the file
            fos = new FileOutputStream(iconFile);
            fos.write(bos.toByteArray());
            fos.flush();

            return WearPackageIconProvider.getUriForPackage(packageName);
        } catch (IOException e) {
            Log.e(TAG, "Could not convert drawable to icon file for package " + packageName, e);
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private class PackageInstallObserver extends IPackageInstallObserver.Stub {
        private Context mContext;
        private PowerManager.WakeLock mWakeLock;
        private int mStartId;
        private PackageInstallObserver(Context context, PowerManager.WakeLock wakeLock,
                int startId) {
            mContext = context;
            mWakeLock = wakeLock;
            mStartId = startId;
        }

        public void packageInstalled(String packageName, int returnCode) {
            if (returnCode >= 0) {
                Log.i(TAG, "Package " + packageName + " was installed.");
            } else {
                Log.e(TAG, "Package install failed " + packageName + ", returnCode " + returnCode);
            }

            // Delete tempFile from the file system.
            File tempFile = WearPackageUtil.getTemporaryFile(mContext, packageName);
            if (tempFile != null) {
                tempFile.delete();
            }

            // Broadcast the "UPDATED" gmscore intent, normally sent by play store.
            // TODO: Remove this broadcast if/when we get the play store to do this for us.
            if (GMS_PACKAGE_NAME.equals(packageName)) {
                Intent gmsInstalledIntent = new Intent(GMS_UPDATED_BROADCAST);
                gmsInstalledIntent.setPackage(GMS_PACKAGE_NAME);
                mContext.sendBroadcast(gmsInstalledIntent);
            }

            finishService(mWakeLock, mStartId);
        }
    }

    private class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        private PowerManager.WakeLock mWakeLock;
        private int mStartId;

        private PackageDeleteObserver(PowerManager.WakeLock wakeLock, int startId) {
            mWakeLock = wakeLock;
            mStartId = startId;
        }

        public void packageDeleted(String packageName, int returnCode) {
            if (returnCode >= 0) {
                Log.i(TAG, "Package " + packageName + " was uninstalled.");
            } else {
                Log.e(TAG, "Package uninstall failed " + packageName + ", returnCode " +
                        returnCode);
            }
            finishService(mWakeLock, mStartId);
        }
    }
}
