package com.sprd.systemupdate;

import java.io.File;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;
import android.os.SystemProperties;
import android.net.Uri;
import java.util.List;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.Environment;
import android.os.EnvironmentEx;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Path;
import android.provider.DocumentsProvider;
import android.app.Activity;
import android.content.ContentResolver;
import android.preference.PreferenceManager;
import java.io.OutputStream;
import java.io.IOException;
import android.content.SharedPreferences;
import android.support.v4.provider.DocumentFile;

public class LatestUpdateActivity extends PreferenceActivity {

    private Preference mVersionPref;
    private Preference mOriginalVersionPref;
    private Preference mDatePref;
    private Preference mSizePref;
    private Preference mReleaseNotePref;
    private Storage mStorage;
    private Context mContext;
    public static String group = "";
    private static final int WRITE_REQUEST_CODE = 1111;
    private static final String TAG = "LatestUpdateActivity";

    @SuppressWarnings("deprecation")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContext = this;
        mStorage = Storage.get(this);

        setContentView(R.layout.list_item_one_button);
        addPreferencesFromResource(R.xml.latest_update);
        getListView().setItemsCanFocus(true);

        mVersionPref = findPreference("lastest_update_version");
        mOriginalVersionPref = findPreference("original_version");
        mDatePref = findPreference("lastest_update_date");
        mSizePref = findPreference("lastest_update_size");
        mReleaseNotePref = findPreference("lastest_update_release_note");

        Button download = (Button) findViewById(R.id.download);
        download.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                String group = getIntent().getStringExtra("group");
                DownloadStateApplication downloadStateApplication = (DownloadStateApplication) getApplication();
                downloadStateApplication.setmCurrentDownload(group);

                if (getIntent().getIntExtra("from_where", -1) == Storage.fromWhere.NOTIFI_NEW) {
                    if (mStorage.getState() == Storage.State.PAUSE_2_DOWNLOADING
                            || mStorage.getState() == Storage.State.DOWNLOADING_2_PAUSE
                            || mStorage.getState() == Storage.State.NIL_2_DOWNLOADING
                            || mStorage.getState() == Storage.State.PAUSE_2_PAUSE) {
                        Toast.makeText(mContext,
                                R.string.please_cancel_current_download,
                                Toast.LENGTH_LONG).show();
                        return;
                    } else {
                        if (mStorage.getTmpLatestVersion() != null) {
                            mStorage.setLatestVersion(mStorage
                                    .getTmpLatestVersionString());
                        } else {
                            Toast.makeText(mContext, R.string.download_failed,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                }
                if (mStorage.checkSdCardState()) {
                    if (mStorage.getState() == Storage.State.DOWNLOADED
                            || mStorage.getState() == Storage.State.DOWNLOADED_SPECIAL
                            || mStorage.getState() == Storage.State.DOWNLOADED_DAILY
                            || mStorage.getState() == Storage.State.DOWNLOADING_2_PAUSE
                            || mStorage.getState() == Storage.State.PAUSE_2_PAUSE
                            || mStorage.getState() == Storage.State.WAIT_UPDATE) {
                        showReDownloadDialog();
                    } else {
                        startDownloadUpdateFile();
                    }
                }

            }

        });
        bindData();
    }

    private void bindData() {
        VersionInfo info = mStorage.getLatestVersion();

        if (getIntent().getIntExtra("from_where", -1) == Storage.fromWhere.NOTIFI_NEW) {
            info = mStorage.getTmpLatestVersion();
        }

        if (info == null) {
            finish();
            return;
        }
        mVersionPref.setSummary(info.mVersion);
        mDatePref.setSummary(info.mDate);
        mOriginalVersionPref.setSummary(SystemProperties.get("ro.build.description"));
        if (info.mSize < 1024) {
            mSizePref.setSummary(Integer.toString(info.mSize) + " bytes");
        } else if (info.mSize < 1024 * 1024) {
            double delta_KB = info.mSize / 1024.0;
            mSizePref.setSummary(String.format("%1$.1f", delta_KB) + " KB");
        } else if (info.mSize >= 1024 * 1024) {
            double delta_MB = info.mSize / 1048576.0;
            mSizePref.setSummary(String.format("%1$.1f", delta_MB) + " MB");
        }

        mReleaseNotePref.setSummary(info.mReleaseNote);
        String urlPath[] = info.mUrl.split("/");
        group = urlPath[urlPath.length-2];
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {

            Intent intent = new Intent(LatestUpdateActivity.this,
                    SystemUpdateActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("group", group);
            startActivity(intent);
            finish();
        }
        return super.onKeyDown(keyCode, event);
    }

    public Dialog onCreateDialog(int id) {
        switch (id) {
        case Storage.SDCARD_LACK_SPACE:
            sdCardLackSpaceDialog(id);
            break;
        case Storage.SDCARD_NOT_MOUNTED:
            sdCardUnmountedDialog(id);
            break;
        default:
            break;
        }

        return null;
    }

    public void sdCardUnmountedDialog(int id) {
        Dialog dialog = new AlertDialog.Builder(this)
                .setMessage(R.string.save_in_internal_storage_due_to_no_sdcard)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int which) {
                                dialog.dismiss();
                                if (mStorage.getStorageState() == Storage.SDCARD_AVALIABLE) {
                                    startDownloadUpdateFile();
                                }
                            }
                        })
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int which) {
                                dialog.dismiss();
                            }
                        }).create();
        dialog.setCancelable(true);
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface dialog) {
                dialog.dismiss();
            }
        });
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    public void sdCardLackSpaceDialog(int id) {
        Dialog dialog = new AlertDialog.Builder(this)
                .setMessage(R.string.save_in_internal_storage_due_to_lack_space)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int which) {
                                dialog.dismiss();
                                if (mStorage.getStorageState() == Storage.SDCARD_AVALIABLE) {
                                    startDownloadUpdateFile();
                                }
                            }
                        })
                .setNegativeButton(R.string.cancel,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int which) {
                                dialog.dismiss();
                            }
                        }).create();
        dialog.setCancelable(true);
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface dialog) {
                dialog.dismiss();
            }
        });
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

    }

    public void startDownloadUpdateFile() {

        if (mStorage.getState() == Storage.State.WAIT_UPDATE) {
            AlarmManager mAlarmManager = (AlarmManager) mContext
                    .getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("sprd.systemupdate.action.ASK_UPGRADE");
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext,
                    0, intent, 0);
            mAlarmManager.cancel(pendingIntent);
        }

        requestSdDirectoryAccess(WRITE_REQUEST_CODE);

    }

    public void showReDownloadDialog() {
        Dialog dialog = new AlertDialog.Builder(this)
                .setMessage(R.string.update_file_will_be_covered)
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                    int which) {
                                dialog.dismiss();
                                startDownloadUpdateFile();
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

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bindData();
    }

    /*bug938661 systemupdate can not use when download ota package on app proprietary catalogues*/
    public  void requestSdDirectoryAccess(int requestCode) {
        for (StorageVolume volume : getVolumes()) {
            File volumePath = volume.getPathFile();
            if (!volume.isPrimary() && (volumePath != null) &&
                    Environment.getExternalStorageState(volumePath).equals(Environment.MEDIA_MOUNTED) &&
                    volumePath.getAbsolutePath().startsWith(EnvironmentEx.getExternalStoragePath().getAbsolutePath())) {
                /*bug1114544 use new SAF issue to get SD write permission for android q*/
                Intent intent = null;
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    intent = volume.createOpenDocumentTreeIntent();
                } else {
                    intent = volume.createAccessIntent(null);
                }
                /*bug1114544 use new SAF issue to get SD write permission for android q*/
                if (intent != null) {
                    startActivityForResult(intent, WRITE_REQUEST_CODE);
                }
            }
        }
    }

    private List<StorageVolume> getVolumes() {
        final StorageManager sm = (StorageManager)mContext.getSystemService(Context.STORAGE_SERVICE);
        final List<StorageVolume> volumes = sm.getStorageVolumes();
        return volumes;
    }

    public void getPersistableUriPermission(Uri uri, Intent data) {
        final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri, takeFlags);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if  ((requestCode == WRITE_REQUEST_CODE && resultCode == -1)) {
            Uri uri =null;
            if (data != null) {
                uri = data.getData();

                String documentId = DocumentsContract.getTreeDocumentId(uri);
                if (!documentId.endsWith(":") || "primary:".equals(documentId)) {
                    Toast.makeText(mContext,
                            R.string.internal_access_failed,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                getPersistableUriPermission(uri, data);
                DocumentFile document = DocumentFile.fromTreeUri(mContext, uri);
                document = document.findFile(Storage.UPDATE_FILE_NAME);
                if(document != null){
                    deleteUpdateFile(Uri.parse(document.getUri().toString()));
                }
                createUpdateFile(uri);
                File updateFile = new File(mStorage.getStoragePath(),
                        Storage.UPDATE_FILE_NAME);
                if (updateFile != null) {
                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                    sharedPreferences.edit().putString("storage_file_path", uri.toString()+"/update.zip").apply();
                }
                mStorage.setState(Storage.State.NIL);
                mStorage.setSize(0);
                startActivity(new Intent(LatestUpdateActivity.this,
                        DownloadingActivity.class));
                finish();
                }
        }
    }

    public void createUpdateFile(Uri uri){
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri));
        DocumentFile rootDir = DocumentFile.fromTreeUri(this, uri);
        try{
            DocumentFile updatezip = rootDir.createFile("application/zip",Storage.UPDATE_FILE_NAME);
        } catch(Exception e){
            Log.e(TAG,"createUpdateFile fail:",e);
        }
    }

    public void deleteUpdateFile(Uri uri) {
        try {
            File updateFile = new File(mStorage.getStoragePath(),Storage.UPDATE_FILE_NAME);
            if (uri != null && updateFile.exists()) {
                 DocumentFile targetDocument = getUpdateDocumentFilePath(mContext, uri);
                 if (targetDocument != null) {
                     DocumentsContract.deleteDocument(getContentResolver(), targetDocument.getUri());
                 }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not delete document ", e);
        }
    }

    public static DocumentFile getUpdateDocumentFilePath(Context context, Uri treeUri) {
        DocumentFile document = DocumentFile.fromTreeUri(context, treeUri);
        document = document.findFile(Storage.UPDATE_FILE_NAME);
        return document;
    }
    /*bug938661 systemupdate can not use when download ota package on app proprietary catalogues*/
}
