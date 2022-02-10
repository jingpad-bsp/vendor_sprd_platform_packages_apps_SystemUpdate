package com.sprd.systemupdate;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Toast;
import android.os.*;
import android.view.View;

public class SystemUpdateActivity extends PreferenceActivity implements
        CheckupdateService.Callback {

   // private SharedPreferences mSharePref;
    private Preference mCheckPref;
    private Preference mSpecialCheckPref;
    private Preference mDailyCheckPref;
    private Preference mSetPref;
    private Preference mCurrentDownloadPref = null;
    private Storage mStorage;
    private CheckupdateService.CheckupdateBinder mBinder;
    private NotificationManager mNotificationManager;
    private Context mContext;
    private Utils utils;
    private static boolean sDialogTouchedValid = true;
    private String group = "";
    public int state = Storage.State.DOWNLOADED;

    public static final int CHECKING_PROGRESS = 102;
    public static final int NO_UPDATE_DIALOG = 103;
    public static final int CHECK_CONNECT_FAILED = 104;
    public static final int CHECK_TIME_OUT = 105;
    public static final int RESUME_OR_CHECK = 106;
    public static final int VERSION_NO_INFO = 107;
    public static final int CHECK_OR_INTO_UPGRADE = 108;
    public static final int CANCEL_OR_UPGRADE = 109;

    private BroadcastReceiver mReceiver;
    private PreferenceScreen prefSet;

    private static final String TAG = "SystemUpdateActivity";

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {

            mBinder = (CheckupdateService.CheckupdateBinder) service;
            if (mBinder != null) {
                mBinder.register(SystemUpdateActivity.this);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinder = null;
        }

    };

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = this;
        utils = new Utils(mContext);
        utils.monitorBatteryState();
        mStorage = Storage.get(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        addPreferencesFromResource(R.xml.check_update);
        prefSet = getPreferenceScreen();
        mCheckPref = findPreference("check_update");
        mSpecialCheckPref = findPreference("check_update_special");
        mDailyCheckPref = findPreference("check_update_daily");
        mSetPref = findPreference("set_update");
        if (mStorage.getState() == Storage.State.DOWNLOADED || mStorage.getState() == Storage.State.DOWNLOADED_SPECIAL || mStorage.getState() == Storage.State.DOWNLOADED_DAILY) {
            if (checkUpdatePackageExist() == false) {
                mStorage.setState(Storage.State.NIL);
            }
        }
        // init after update
        if (null != mStorage.getLatestVersion()){
            if ( null != mStorage.getLatestVersion().mVersion && mStorage.getLatestVersion().mVersion.toString().equals(SystemProperties.get("ro.build.description").toString())){
                mStorage.setState(Storage.State.NIL);
                mStorage.setToken(null);
                mStorage.setDeviceId(null);
            }
        }
       // mSharePref = PreferenceManager.getDefaultSharedPreferences(this);
        Intent intent = getIntent();
        group = intent.getStringExtra("group");
        getCurrentDownloadPref();
        showCheckPrefSummary(mCurrentDownloadPref);
    }

    @Override
    protected void onResume() {
        if (Utils.DEBUG) {
            Log.i(TAG, "onResume");
            Log.i(TAG, "Storage State" + mStorage.getState());
        }
        getCurrentDownloadPref();
        if (mCurrentDownloadPref == null){
            if (DownloadingService.group.equals("normal")){
                mCurrentDownloadPref = mCheckPref;
            } else if (DownloadingService.group.equals("special")){
                mCurrentDownloadPref = mSpecialCheckPref;
            } else if (DownloadingService.group.equals("daily")){
                mCurrentDownloadPref = mDailyCheckPref;
            }
        }

        if (mStorage.getState() != Storage.State.DOWNLOADED && mStorage.getState() != Storage.State.DOWNLOADED_SPECIAL && mStorage.getState() != Storage.State.DOWNLOADED_DAILY){
            if (mCurrentDownloadPref == mCheckPref){
                setPrefDisable(mSpecialCheckPref);
                setPrefDisable(mDailyCheckPref);
            } else if (mCurrentDownloadPref == mSpecialCheckPref){
                setPrefDisable(mCheckPref);
                setPrefDisable(mDailyCheckPref);
            } else if (mCurrentDownloadPref == mDailyCheckPref){
                setPrefDisable(mSpecialCheckPref);
                setPrefDisable(mCheckPref);
            }
        }

        if (mSpecialCheckPref != null) {
            if (!"1".equals(SystemProperties.get("persist.sys.special.enable","0"))) {
                prefSet.removePreference(mSpecialCheckPref);
            } else {
                prefSet.addPreference(mSpecialCheckPref);
            }
        }
        if (mDailyCheckPref != null) {
            if (!"1".equals(SystemProperties.get("persist.sys.daily.enable","0"))) {
                prefSet.removePreference(mDailyCheckPref);
            } else {
                prefSet.addPreference(mDailyCheckPref);
            }
        }
        if (getIntent().getIntExtra("from_where", Storage.fromWhere.NIL) == Storage.fromWhere.NOTIFI_OLD) {
            getIntent().putExtra("from_where", Storage.fromWhere.NIL);
            Toast toast = Toast.makeText(mContext,
                    R.string.push_version_is_wait_update, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();

        }
        Intent mIntent = new Intent();
        mIntent.putExtra("group", group);
        mIntent.setAction("sprd.systemupdate.action.CHECKUPDATE");
        mIntent.setPackage(getPackageName());
        bindService(mIntent, mServiceConnection, Service.BIND_AUTO_CREATE);
        showCheckPrefSummary(mCurrentDownloadPref);
        super.onResume();

        IntentFilter intentFilter = new IntentFilter(
                "sprd.systemupdate.action.DOWNLOAD_RESULT");
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(
                        "sprd.systemupdate.action.DOWNLOAD_RESULT")) {
                    showCheckPrefSummary(mCurrentDownloadPref);
                }

            }
        };
        this.registerReceiver(mReceiver, intentFilter);

    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferences,
            Preference preference) {
        if (preference == mCheckPref) {
            group = "normal";

            if (Utils.DEBUG) {
                Log.i(TAG, "onPreferenceTreeClick mStorage.getState() : "
                        + mStorage.getState());
            }

            switch (mStorage.getState()) {
            case Storage.State.DOWNLOADING_2_PAUSE:
                showDialog(RESUME_OR_CHECK);
                break;
            case Storage.State.NIL_2_DOWNLOADING:
            case Storage.State.PAUSE_2_DOWNLOADING:
                startActivity(new Intent(SystemUpdateActivity.this,
                        DownloadingActivity.class));
                finish();
                break;
            case Storage.State.DOWNLOADED:
                showDialog(CHECK_OR_INTO_UPGRADE);
                break;
            case Storage.State.WAIT_UPDATE:
                if (mCheckPref.toString().equals(getResources().getString(R.string.waiting_update))){
                    showDialog(CANCEL_OR_UPGRADE);
                    break;
                }
            default:
                Intent mIntent = new Intent();
                mIntent.putExtra("group", group);
                mIntent.setAction("sprd.systemupdate.action.CHECKUPDATE");
                mIntent.setPackage(getPackageName());
                startService(mIntent);
                break;
            }
        } else if (preference == mSpecialCheckPref) {
              group = "special";

            if (Utils.DEBUG) {
                Log.i(TAG, "onPreferenceTreeClick mStorage.getState() : "
                        + mStorage.getState());
            }
            switch (mStorage.getState()) {
            case Storage.State.DOWNLOADING_2_PAUSE:
                showDialog(RESUME_OR_CHECK);
                break;
            case Storage.State.NIL_2_DOWNLOADING:
            case Storage.State.PAUSE_2_DOWNLOADING:
                startActivity(new Intent(SystemUpdateActivity.this,
                        DownloadingActivity.class));
                finish();
                break;
            /* tmp:debug http://bugzilla.spreadtrum.com/bugzilla/show_bug.cgi?id=565658
                change DOWNLOADED to DOWNLOADED_SPECIAL*/
            case Storage.State.DOWNLOADED_SPECIAL:
                showDialog(CHECK_OR_INTO_UPGRADE);
                break;
            case Storage.State.WAIT_UPDATE:
                if (mSpecialCheckPref.toString().equals(getResources().getString(R.string.waiting_update))){
                    showDialog(CANCEL_OR_UPGRADE);
                    break;
                }
            default:
                Intent mIntent = new Intent();
                mIntent.putExtra("group", group);
                mIntent.setAction("sprd.systemupdate.action.CHECKUPDATE");
                mIntent.setPackage(getPackageName());
                startService(mIntent);
                break;
                }
        } else if (preference == mDailyCheckPref) {
              group = "daily";

            // @tmp: debug http://bugzilla.spreadtrum.com/bugzilla/show_bug.cgi?id=565658
            Log.e("ql","mDailyCheckPref:" + preference.toString());
            // @
            if (Utils.DEBUG) {
                Log.i(TAG, "onPreferenceTreeClick mStorage.getState() : "
                        + mStorage.getState());
            }
            switch (mStorage.getState()) {
            case Storage.State.DOWNLOADING_2_PAUSE:
                showDialog(RESUME_OR_CHECK);
                break;
            case Storage.State.NIL_2_DOWNLOADING:
            case Storage.State.PAUSE_2_DOWNLOADING:
                startActivity(new Intent(SystemUpdateActivity.this,
                        DownloadingActivity.class));
                finish();
                break;
            case Storage.State.DOWNLOADED_DAILY:
                showDialog(CHECK_OR_INTO_UPGRADE);
                break;
            case Storage.State.WAIT_UPDATE:
                if (mDailyCheckPref.toString().equals(getResources().getString(R.string.waiting_update))){
                    showDialog(CANCEL_OR_UPGRADE);
                    break;
                }
            default:
                Intent mIntent = new Intent();
                mIntent.putExtra("group", group);
                mIntent.setAction("sprd.systemupdate.action.CHECKUPDATE");
                mIntent.setPackage(getPackageName());
                startService(mIntent);
                break;
                }
        } else if (preference == mSetPref) {
            startActivity(new Intent(SystemUpdateActivity.this,
                    SettingActivity.class));
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    public void startcheck() {
        showDialog(CHECKING_PROGRESS);
    }

    @SuppressWarnings("deprecation")
    public void endcheck(Integer i) {
        removeDialog(CHECKING_PROGRESS);
        if (i == CheckupdateService.CHECK_UPDATE_HAS_UPDATE) {

            Toast toast = Toast.makeText(getApplicationContext(),
                    R.string.new_version, Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();

            Intent intent = new Intent(SystemUpdateActivity.this,
                    LatestUpdateActivity.class);
            intent.putExtra("group", group);
            intent.putExtra("fromMainActivity", true);
            startActivity(intent);
            finish();
        } else if (i == CheckupdateService.CHECK_UPDATE_NO_UPDATE) {
            AlertDialog dialog = new AlertDialog.Builder(this).setMessage(
                    R.string.check_no_update).create();
            dialog.setCanceledOnTouchOutside(false);
            AutoCloseDialog d = new AutoCloseDialog(dialog);
            d.show(2000);
        } else if (i == CheckupdateService.CHECK_UPDATE_CONNECT_ERROR) {
            showDialog(CHECK_CONNECT_FAILED);
        } else if (i == CheckupdateService.CHECK_UPDATE_NEED_REGISTER) {
            showDialog(VERSION_NO_INFO);
        } else if (i == CheckupdateService.CHECK_UPDATE_CONNECT_TIME_OUT
                || i == CheckupdateService.CHECK_UPDATE_SEED_EXPRIED) {
            showDialog(CHECK_TIME_OUT);
        } else if (i == CheckupdateService.CHECK_UPDATE_VERSION_NOT_FOUND) {
            showDialog(VERSION_NO_INFO);
        }
    }

    @SuppressWarnings("deprecation")
    public Dialog onCreateDialog(int id) {
        if (id == CHECKING_PROGRESS) {
            ProgressDialog progressdialog = new ProgressDialog(this);
            progressdialog.setTitle(getText(R.string.please_wait));
            progressdialog.setMessage(getResources().getString(
                    R.string.checking_update));
            progressdialog.setIndeterminate(false);
            progressdialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            progressdialog.setCanceledOnTouchOutside(false);
            progressdialog.setButton(getString(R.string.cancel),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            mBinder.cancel();
                        }
                    });
            return progressdialog;
        }
        if (id == CHECK_TIME_OUT) {
            Dialog dialog = new AlertDialog.Builder(this)
                    .setMessage(R.string.check_time_out)
                    .setPositiveButton(R.string.retry,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    Intent mIntent = new Intent();
                                    mIntent.putExtra("group", group);
                                    mIntent.setAction("sprd.systemupdate.action.CHECKUPDATE");
                                    mIntent.setPackage(getPackageName());
                                    startService(mIntent);
                                    dialog.dismiss();
                                }
                            })
                    .setNegativeButton(R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    dialog.dismiss();
                                }
                            }).create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
        if (id == CHECK_CONNECT_FAILED) {
            Dialog dialog = new AlertDialog.Builder(this)
                    .setMessage(R.string.check_connect_failed)
                    .setPositiveButton(R.string.retry,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    Intent mIntent = new Intent();
                                    mIntent.putExtra("group", group);
                                    mIntent.setAction("sprd.systemupdate.action.CHECKUPDATE");
                                    mIntent.setPackage(getPackageName());
                                    startService(mIntent);
                                    dialog.dismiss();

                                }
                            })
                    .setNegativeButton(R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    dialog.dismiss();
                                }
                            }).create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
        if (id == RESUME_OR_CHECK) {
            Dialog dialog = new AlertDialog.Builder(this)
                    .setMessage(R.string.which_do_you_want)
                    .setPositiveButton(R.string.resume,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    /*if (sDialogTouchedValid == false) {
                                        sDialogTouchedValid = true;
                                        return;
                                    }
                                    sDialogTouchedValid = false;*/
                                    dialog.dismiss();
                                    finish();
                                    Intent intent = new Intent(
                                            SystemUpdateActivity.this,
                                            DownloadingActivity.class);
                                    intent.putExtra("fromMainActivity", true);
                                    startActivity(intent);
                                }
                            })
                    .setNegativeButton(R.string.new_check,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    /*if (sDialogTouchedValid == false) {
                                        sDialogTouchedValid = true;
                                        return;
                                    }
                                    sDialogTouchedValid = false;*/
                                    dialog.dismiss();
                                    mNotificationManager.cancel(0);
                                    Intent mIntent = new Intent();
                                    mIntent.putExtra("group", group);
                                    mIntent.setAction("sprd.systemupdate.action.CHECKUPDATE");
                                    mIntent.setPackage(getPackageName());
                                    startService(mIntent);
                                }
                            }).create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
        if (id == VERSION_NO_INFO) {
            Dialog dialog = new AlertDialog.Builder(this)
                    .setMessage(R.string.version_no_info)
                    .setPositiveButton(R.string.ok,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    dialog.dismiss();
                                }
                            }).create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }

        if (id == CHECK_OR_INTO_UPGRADE) {
            Dialog dialog = new AlertDialog.Builder(this)
                    .setMessage(R.string.which_do_you_want)
                    .setPositiveButton(R.string.into_upgrade,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    dialog.dismiss();
                                    Intent intent = new Intent(SystemUpdateActivity.this,
                                            UpgradeActivity.class);
                                    intent.putExtra("storageState", mStorage.getState());
                                    startActivity(intent);
                                    finish();
                                }
                            })
                    .setNegativeButton(R.string.recheck,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    dialog.dismiss();
                                    Intent mIntent = new Intent();
                                    mIntent.putExtra("group", group);
                                    mIntent.setAction("sprd.systemupdate.action.CHECKUPDATE");
                                    mIntent.setPackage(getPackageName());
                                    startService(mIntent);
                                }
                            }).create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }

        if (id == CANCEL_OR_UPGRADE) {
            if (mCurrentDownloadPref != null){
                if (mCurrentDownloadPref == mCheckPref){
                    state = Storage.State.DOWNLOADED;
                }else if (mCurrentDownloadPref == mSpecialCheckPref){
                    state = Storage.State.DOWNLOADED_SPECIAL;
                }else if (mCurrentDownloadPref == mDailyCheckPref){
                    state = Storage.State.DOWNLOADED_DAILY;
                }
            }
            Dialog dialog = new AlertDialog.Builder(this)
                    .setMessage(R.string.which_do_you_want)
                    .setPositiveButton(R.string.upgrade_now,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    mCurrentDownloadPref.setTitle(R.string.downloaded);
                                    mStorage.setState(state);
                                    dialog.dismiss();
                                    if (utils.isUpdateFileExist()) {
                                        if (utils.isBatteryPowerEnough()) {
                                            utils.ShowUpgradeDialog(state);
                                        }
                                    }

                                }
                            })
                    .setNegativeButton(R.string.cancel_upgrade,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    mCurrentDownloadPref.setTitle(R.string.downloaded);
                                    mStorage.setState(state);
                                    dialog.dismiss();
                                    onCreate(null);
                                    AlarmManager mAlarmManager = (AlarmManager) mContext
                                            .getSystemService(Context.ALARM_SERVICE);
                                    Intent intent = new Intent(
                                            "sprd.systemupdate.action.ASK_UPGRADE");
                                    PendingIntent pendingIntent = PendingIntent
                                            .getBroadcast(mContext, 0, intent,
                                                    0);
                                    mAlarmManager.cancel(pendingIntent);
                                }
                            }).create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
        return null;
    }

    public class AutoCloseDialog {

        private AlertDialog dialog;
        private ScheduledExecutorService executor = Executors
                .newSingleThreadScheduledExecutor();

        public AutoCloseDialog(AlertDialog dialog) {
            this.dialog = dialog;
        }

        public void show(long duration) {

            Runnable runner = new Runnable() {
                @Override
                public void run() {
                    dialog.dismiss();
                }
            };
            executor.schedule(runner, duration, TimeUnit.MILLISECONDS);
            dialog.show();
        }

    }

    @Override
    protected void onPause() {
        if (mBinder != null) {
            mBinder.unregister();
        }
        showCheckPrefSummary(mCurrentDownloadPref);
        unbindService(mServiceConnection);
        this.unregisterReceiver(mReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mStorage.getState() == Storage.State.WAIT_UPDATE || mStorage.getState() == Storage.State.DOWNLOADED || mStorage.getState() == Storage.State.DOWNLOADED_SPECIAL || mStorage.getState() == Storage.State.DOWNLOADED_DAILY){
            Intent stopIntent = new Intent();
            stopIntent.setAction("sprd.systemupdate.action.DOWNLOADING");
            stopIntent.setPackage(getPackageName());
            stopService(stopIntent);
        }
        utils.cancelMonitorBatteryState();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0)
                || (keyCode == KeyEvent.KEYCODE_HOME && event.getRepeatCount() == 0)) {

            if (mStorage.getState() == Storage.State.DOWNLOADING_2_PAUSE) {
                mStorage.setState(Storage.State.PAUSE_2_PAUSE);
            }
            finish();
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showCheckPrefSummary(Preference preference) {
        String preferenceTitle = getResources().getString(R.string.check);
        if (preference != null){
            if (preference == mSpecialCheckPref){
                preferenceTitle =  getResources().getString(R.string.special_check);
            } else if(preference == mDailyCheckPref){
                preferenceTitle = getResources().getString(R.string.daily_check);
            }
            mCheckPref.setTitle(R.string.check);
            mCheckPref.setSummary("");
        } else{
            preference = mCheckPref;
        }
            preference.setTitle("");
            preference.setSummary("");
            switch (mStorage.getState()) {
            case Storage.State.DOWNLOADING_2_PAUSE:
            case Storage.State.PAUSE_2_PAUSE:
                mStorage.setState(Storage.State.DOWNLOADING_2_PAUSE);
                preference.setTitle(getResources().getString(R.string.pausing)
                        + ":");
                preference.setSummary(mStorage.getLatestVersion().mVersion);
                break;
            case Storage.State.PAUSE_2_DOWNLOADING:
            case Storage.State.NIL_2_DOWNLOADING:
                preference.setTitle(getResources().getString(R.string.downloading)
                        + ":");
                preference.setSummary(mStorage.getLatestVersion().mVersion);
                break;
            case Storage.State.DOWNLOADED:
                mCheckPref.setTitle(R.string.downloaded);
                mSpecialCheckPref.setTitle(R.string.special_check);
                mDailyCheckPref.setTitle(R.string.daily_check);
                break;
            case Storage.State.DOWNLOADED_SPECIAL:
                mSpecialCheckPref.setTitle(R.string.downloaded);
                mCheckPref.setTitle(R.string.check);
                mDailyCheckPref.setTitle(R.string.daily_check);
                break;
            case Storage.State.DOWNLOADED_DAILY:
                mDailyCheckPref.setTitle(R.string.downloaded);
                mCheckPref.setTitle(R.string.check);
                mSpecialCheckPref.setTitle(R.string.special_check);
                break;
            case Storage.State.WAIT_UPDATE:
                preference.setTitle(R.string.waiting_update);
                break;
            default:
                preference.setTitle(preferenceTitle);
                break;
            }
    }

    private boolean checkUpdatePackageExist() {

        String path = mStorage.getStorageFilePath();
        if (path == null) {
            return false;
        }

        try {
            File f = new File(path);
            if (!f.exists()) {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    private void getCurrentDownloadPref(){
        if(group == null || group.equals("")){
            VersionInfo info = mStorage.getLatestVersion();
            if (info != null){
                String urlPath[] = info.mUrl.split("/");
                group = urlPath[urlPath.length-2];
            }
        }
        if (group != null && mStorage.getState() != Storage.State.NIL){
            if (group.equals("special")){
                mCurrentDownloadPref = mSpecialCheckPref;
            } else if (group.equals("daily")){
                mCurrentDownloadPref = mDailyCheckPref;
            } else if (group.equals("normal")){
                mCurrentDownloadPref = mCheckPref;
            }
        } else {
            DownloadStateApplication downloadStateApplication = (DownloadStateApplication) getApplication();
            String mCurrentDownload = downloadStateApplication.getmCurrentDownload();
            if (mCurrentDownload != null && mStorage.getState() != Storage.State.NIL && mStorage.getState() != Storage.State.DOWNLOADING_2_NIL && mStorage.getState() != Storage.State.PAUSE_2_NIL){
                if (mCurrentDownload.equals("special")){
                    mCurrentDownloadPref = mSpecialCheckPref;
                } else if (mCurrentDownload.equals("daily")){
                    mCurrentDownloadPref = mDailyCheckPref;
                } else if (mCurrentDownload.equals("normal")){
                    mCurrentDownloadPref = mCheckPref;
                }
            }
        }
    }

    private void setPrefDisable(Preference preference){
        preference.setEnabled(false);
        preference.setShouldDisableView(true);
    }
}
