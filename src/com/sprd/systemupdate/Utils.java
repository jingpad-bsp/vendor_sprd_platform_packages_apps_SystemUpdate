package com.sprd.systemupdate;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import android.R.bool;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;
import android.view.Gravity;
import android.os.RecoverySystem;
import android.os.EnvironmentEx;

public class Utils {

    private Context context;
    private Storage mStorage;
    private BroadcastReceiver batteryLevelRcvr;
    private int levelpower;
    private IntentFilter batteryLevelFilter;

    private File RECOVERY_DIR = new File("cache/recovery");
    private File COMMAND_FILE = new File(RECOVERY_DIR, "command");

    public static final boolean DEBUG = false;
    private Toast mToast;

    interface DialogId {
        int UPGRADE_OR_NOT = 1;
    }

    private static final String TAG = "SystemUpdate-Utils";

    public Utils(Context cont) {
        context = cont;
        mStorage = Storage.get(context);
    }

    public void monitorBatteryState() {
        if (context == null) {
            return;
        }
        Log.d(TAG, "Context" + context);
        batteryLevelRcvr = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                int rawlevel = intent.getIntExtra("level", -1);
                int scale = intent.getIntExtra("scale", -1);
                int level = -1; // percentage, or -1 for unknown
                if (rawlevel >= 0 && scale > 0) {
                    level = (rawlevel * 100) / scale;
                }
                levelpower = level;
            }
        };
        batteryLevelFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(batteryLevelRcvr, batteryLevelFilter);
    }

    public void cancelMonitorBatteryState(){
        context.unregisterReceiver(batteryLevelRcvr);
    }

    public boolean isBatteryPowerEnough() {
        if (levelpower >= 35) {
            return true;
        } else {
            mToast = showToast(context, mToast,
                    context.getString(R.string.battery_power_not_enough), Toast.LENGTH_LONG);
            return false;
        }
    }

    public boolean isUpdateFileExist() {

        if (mStorage.getStorageState() == Storage.SDCARD_NOT_MOUNTED) {
            mToast = showToast(context, mToast,
                    context.getString(R.string.sd_card_not_mounted), Toast.LENGTH_SHORT);
            return false;
        }

        String path = mStorage.getStorageFilePath();
        if (path == null) {
            return false;
        }

        try {
            File f = new File(path);
            if (!f.exists()) {
                mToast = showToast(context, mToast,
                        context.getString(R.string.update_file_not_exist), Toast.LENGTH_SHORT);
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        return true;

    }

    public Dialog onCreateDialog(int id) {
        int state = Storage.State.DOWNLOADED;
        switch (id) {
        case DialogId.UPGRADE_OR_NOT:
            ShowUpgradeDialog(state);
            break;

        default:
            break;
        }
        return null;
    }

    public void ShowUpgradeDialog(int state) {
        mStorage.setState(state);
        Log.i(TAG,"mStorageState="+mStorage.getState());
        Dialog dialog = new AlertDialog.Builder(context)
                .setMessage(R.string.is_now_upgrade)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int which) {
                                dialog.dismiss();
                                installUpdateFile(state);
                            }
                        })
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int which) {
                                dialog.dismiss();
                                AlarmManager mAlarmManager = (AlarmManager) context
                                        .getSystemService(Context.ALARM_SERVICE);
                                Intent intent = new Intent(
                                        "sprd.systemupdate.action.ASK_UPGRADE");
                                PendingIntent pendingIntent = PendingIntent
                                        .getBroadcast(context, 0, intent, 0);
                                mAlarmManager.cancel(pendingIntent);
                            }
                        }).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    public void installUpdateFile(int state) {

        RECOVERY_DIR.mkdirs();
        COMMAND_FILE.delete();

        /*try {
            FileWriter command = new FileWriter(COMMAND_FILE);
            Log.i(TAG, "--update_package=" + mStorage.getStorageFilePath());
            command.write("--update_package=/sdcard0/update.zip");
            Log.i(TAG, "command is write");
            command.write("\n");
            command.close();
        } catch (IOException e) {
            Log.e(TAG, "command is failed");
            e.printStackTrace();
        }*/
        File storageDirectory = mStorage.getSdCardDirectory();
        File file = new File(storageDirectory, "update.zip");
        if(file.exists()) {
            try {
                RecoverySystem.installPackage(context, file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mStorage.setUpgrade(true);
        mStorage.setState(state);

        /*PowerManager pm = (PowerManager) context
                .getSystemService(Context.POWER_SERVICE);
        pm.reboot("recovery");*/
    }

    public static Toast showToast(Context context, Toast toast, String msg, int duration) {
        if (toast == null) {
            Toast newtoast = Toast.makeText(context, msg, duration);
            newtoast.setGravity(Gravity.CENTER, 0, 0);
            newtoast.show();
            return newtoast;
        } else {
            toast.cancel();
            toast = Toast.makeText(context, msg, duration);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
            return toast;
        }
    }
}
