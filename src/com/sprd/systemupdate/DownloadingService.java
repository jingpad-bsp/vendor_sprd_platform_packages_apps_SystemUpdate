package com.sprd.systemupdate;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.support.v4.app.NotificationCompat;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import java.io.OutputStream;
import android.support.v4.provider.DocumentFile;
import android.provider.DocumentsContract;

public class DownloadingService extends Service {
    public Callback mCallback;
    private Context mContext;
    private Storage mStorage;
    private VersionInfo mInfo;
    private DownloadTask mDownloadTask;
    private TokenVerification mTokenVerification;
    private NotificationManager mNotificationManager;
    private NotificationChannel mSystemUpdateChannel;
    private Intent mNotifyIntent;
    public static final int MAX_RETRY_COUNT = 3;
    public static final int BUFFER_SIZE = 10240;
    public static final int PUBLISH_STEPS = 100;

    public static final String UPDATE_FILE_NAME = "update.zip";

    public static final String BEGIN_DOWNLOAD_URL = "begin_download_notify";
    public static final String FULL_DOWNLOAD_URL = "full_download_notify";
    public static final String TAG = "SystemUpdate-DownloadingService";

    public static final int SET_TWO_STATE_BUTTON_ENABLED = 0x01;
    public static final int SET_TWO_STATE_BUTTON_PAUSE = 0x02;
    public static final int SET_TWO_STATE_BUTTON_RESUMED = 0x03;
    public static final int UPDATE_FILE_BEING_DELETED = 0x04;

    private static final int ONE_TENTH_MB = 1024 * 1024 / 10;

    private boolean updateFileState = true;
    public static String group = "";
    private static final int WRITE_REQUEST_CODE = 1111;
    public int storageState = 14;

    public interface Callback {

        public void updateProgress(int progress);

        public void endDownload(boolean succ);

        public void setTwoStateButtonEnabled();

        public void setTwoStateButtonPaused();

        public void setTwoStateButtonResumed();

        public void showUpdateFileDeletedDialog();
    }

    private DownloadingBinder binder = new DownloadingBinder();

    public class DownloadingBinder extends Binder {

        public void register(Callback callback) {
            mCallback = callback;
        }

        public void unregister() {
            if (mCallback != null) {
                mCallback = null;
            }
        }

        public void cancel() {
            if (mDownloadTask != null) {
                mDownloadTask.cancel(true);
            }
        }

    }

    public Handler DownloadHandler = new Handler() {
        public void handleMessage(Message msg) {

            Log.i(TAG, "InhandlerMessage+ msg.what" + msg.what);

            Log.i(TAG, "mCallback:" + mCallback);
            if (mCallback != null) {
                switch (msg.what) {
                case SET_TWO_STATE_BUTTON_ENABLED:
                    mCallback.setTwoStateButtonEnabled();
                    break;
                case SET_TWO_STATE_BUTTON_PAUSE:
                    mCallback.setTwoStateButtonPaused();
                    break;
                case SET_TWO_STATE_BUTTON_RESUMED:
                    mCallback.setTwoStateButtonResumed();
                    break;
                case UPDATE_FILE_BEING_DELETED:
                    mCallback.showUpdateFileDeletedDialog();
                default:
                    break;
                }
            }

            super.handleMessage(msg);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (Utils.DEBUG) {
            Log.i(TAG, "DownloadingService--onBind" + binder);
        }
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotifyIntent = new Intent(DownloadingService.this,
                DownloadingActivity.class);
        mNotifyIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext = this;
        mStorage = Storage.get(mContext);
        mTokenVerification = new TokenVerification(mContext);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (Utils.DEBUG) {
            Log.i(TAG, "DownloadingService--onStartCommand");
        }

        mInfo = mStorage.getLatestVersion();
        mDownloadTask = new DownloadTask();
        mDownloadTask.execute();

        return START_STICKY;
    }

    class DownloadTask extends AsyncTask<Object, Integer, Boolean> {
        private PendingIntent mPendingIntent = PendingIntent.getActivity(
                DownloadingService.this, 0, mNotifyIntent, 0);

        public void onPreExecute() {
            if (mInfo == null) {
                return;
            }

            String title = getResources().getString(R.string.downloading);
            String titleAndPercentage = getTitleAndPercentage(title);

            @SuppressWarnings("deprecation")
            NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    DownloadingService.this)
                    .setAutoCancel(false)
                    .setContentTitle(titleAndPercentage)
                    .setContentText(mInfo.mVersion)
                    .setWhen(System.currentTimeMillis()).setTicker(title)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress( mInfo.mSize, 0, false)
                    .setContentIntent(mPendingIntent).setOngoing(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                createNotificationChannel();
                builder.setChannelId(mSystemUpdateChannel.getId());
            }
            Notification notification = builder.build();
            mNotificationManager.notify(0, notification);

            try {
                noticeServer(BEGIN_DOWNLOAD_URL);
            } catch (NoSuchAlgorithmException e) {
                Log.e("DOWNLOAD", "noticeServer" + e.toString());
            }
        }

        public Boolean doInBackground(Object... v) {
            return getDelta();
        }

        public void onPostExecute(Boolean success) {

            if (updateFileState == false) {
                if (mCallback == null) {
                    mStorage.setSize(0);
                    final NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    notificationManager.cancelAll();
                    mStorage.setState(Storage.State.NIL);
                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
                    String uri = sharedPreferences.getString("storage_file_path", null);
                    deleteUpdateFile(Uri.parse(uri));

                    Intent intent = new Intent(
                            "sprd.systemupdate.action.UPDATE_FILE_DELETED");
                    sendBroadcast(intent);
                } else {
                    mCallback.showUpdateFileDeletedDialog();
                }
                updateFileState = true;
                return;
            }

            Message message = new Message();
            mNotificationManager.cancel(0);
            if (success) {
                VersionInfo info = mStorage.getLatestVersion();
                String urlPath[] = info.mUrl.split("/");
                String group = urlPath[urlPath.length-2];
                if (group.equals("special")){
                    mStorage.setState(Storage.State.DOWNLOADED_SPECIAL);
                } else if (group.equals("daily")){
                    mStorage.setState(Storage.State.DOWNLOADED_DAILY);
                } else if (group.equals("normal")){
                    mStorage.setState(Storage.State.DOWNLOADED);
                } else {
                    mStorage.setState(storageState);
                }
                try {
                    noticeServer(FULL_DOWNLOAD_URL);
                } catch (NoSuchAlgorithmException e) {
                    Log.e(TAG, "noticeServer " + e.toString());
                }
            } else {
                if (mStorage.getState() == Storage.State.NIL_2_DOWNLOADING
                        || mStorage.getState() == Storage.State.PAUSE_2_DOWNLOADING) {
                    mStorage.setState(Storage.State.DOWNLOADING_2_PAUSE);

                    message.what = SET_TWO_STATE_BUTTON_RESUMED;
                    if (DownloadHandler != null) {
                        DownloadHandler.sendMessage(message);
                    }
                } else if (mStorage.getState() == Storage.State.NIL) {
                    // mStorage.setState(Storage.State.NIL);

                } else if (mStorage.getState() == Storage.State.DOWNLOADING_2_PAUSE) {
                    message.what = SET_TWO_STATE_BUTTON_RESUMED;
                    if (DownloadHandler != null) {
                        DownloadHandler.sendMessage(message);
                    }
                }
            }

            if (mCallback != null) {
                mCallback.endDownload(success);
            } else {
                Intent intent = new Intent(
                        "sprd.systemupdate.action.DOWNLOAD_RESULT");
                intent.putExtra("result", success);
                sendBroadcast(intent);
            }

        }

        @Override
        protected void onCancelled() {
            Log.i(TAG,
                    "DownloadingService--onCancelled()" + mStorage.getState());
            if (mInfo == null) {
                return;
            }
            group = "";
            if (mStorage.getSize() == mInfo.mSize) {
                mNotificationManager.cancel(0);
                if (mCallback != null) {
                    mCallback.endDownload(true);
                }
                return;
            }

            if (mStorage.getState() == Storage.State.PAUSE_2_DOWNLOADING
                    || mStorage.getState() == Storage.State.NIL_2_DOWNLOADING) {
                mStorage.setState(Storage.State.DOWNLOADING_2_PAUSE);
                Log.i(TAG,
                        "DownloadingService--onCancelled()_again"
                                + mStorage.getState());

            }
            String title = getResources().getString(R.string.pause);
            String titleAndPercentage = getTitleAndPercentage(title);

            @SuppressWarnings("deprecation")
            NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    DownloadingService.this)
                    .setAutoCancel(false)
                    .setContentTitle(titleAndPercentage)
                    .setContentText(mInfo.mVersion)
                    .setWhen(System.currentTimeMillis()).setTicker(title)
                    .setSmallIcon(R.drawable.stat_download_pause)
                    .setProgress(mInfo.mSize, mStorage.getSize(), false)
                    .setContentIntent(mPendingIntent).setOngoing(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                createNotificationChannel();
                builder.setChannelId(mSystemUpdateChannel.getId());
            }
            Notification notification = builder.build();
            mNotificationManager.notify(0, notification);
            if (Utils.DEBUG) {
                Log.d(TAG, "cancelled_set_button_enabled");
            }
            Message message = new Message();
            message.what = SET_TWO_STATE_BUTTON_ENABLED;
            if (DownloadHandler != null) {
                DownloadHandler.sendMessage(message);
            }

        }

        @Override
        public void onProgressUpdate(Integer... values) {
            if (mInfo == null) {
                return;
            }
            String urlPath[] = mInfo.mUrl.split("/");
            group = urlPath[urlPath.length-2];
            int progress = values[0];

            String title = getResources().getString(R.string.downloading);
            String titleAndPercentage = getTitleAndPercentage(title);

            @SuppressWarnings("deprecation")
            NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    DownloadingService.this)
                    .setAutoCancel(false)
                    .setContentTitle(titleAndPercentage)
                    .setContentText(mInfo.mVersion)
                    .setWhen(System.currentTimeMillis()).setTicker(title)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(mInfo.mSize, progress, false)
                    .setContentIntent(mPendingIntent).setOngoing(true);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                createNotificationChannel();
                builder.setChannelId(mSystemUpdateChannel.getId());
            }
            Notification notification = builder.build();
            mNotificationManager.notify(0, notification);
            if (mCallback != null) {
                mCallback.updateProgress(progress);
            }
        }

        private Boolean getDelta() {
            if (mInfo == null) {
                return false;
            }
            int publishStep = mInfo.mSize / PUBLISH_STEPS;
            int nextThreshHold = publishStep;
            if (new File(mStorage.getStorageFilePath()).exists()) {
                mStorage.setSize((int) new File(mStorage.getStorageFilePath())
                        .length());
            }

            DefaultHttpClient client = new DefaultHttpClient();
            HttpParams httpParams = client.getParams();
            if (httpParams != null) {
                httpParams.setIntParameter(HttpConnectionParams.SO_TIMEOUT,
                        mStorage.getTimeOut());
                httpParams.setIntParameter(
                        HttpConnectionParams.CONNECTION_TIMEOUT,
                        mStorage.getTimeOut());
            } else {
                if (Utils.DEBUG) {
                    Log.i(TAG, "httpParams: " + httpParams);
                }
                return false;
            }

            int total = 0;
            int pre_download = 0;
            InputStream is = null;
            OutputStream out = null;

            try {
                String url = mInfo.mUrl.replace(" ", "%20");
                HttpGet get = new HttpGet(url);
                if (mStorage.getSize() != 0) {
                    pre_download = total = mStorage.getSize();
                    get.addHeader("Range", "bytes=" + total + "-" + mInfo.mSize);
                    publishProgress(total);
                }

                if (Utils.DEBUG) {
                    Log.i(TAG, "about to get:" + mInfo.mUrl);
                    Log.i(TAG, "about to get:" + url);
                }

                HttpResponse response = client.execute(new HttpHost(
                        PushService.SERVER_ADDR, 3000), get);

                if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK
                        && response.getStatusLine().getStatusCode() != HttpStatus.SC_PARTIAL_CONTENT) {
                    Log.e(TAG, "DownloadTask--StatusCode"
                            + response.getStatusLine().getStatusCode());
                    return false;
                }

                if (mStorage.getState() == Storage.State.DOWNLOADING_2_PAUSE) {
                    mStorage.setState(Storage.State.PAUSE_2_DOWNLOADING);
                } else if (mStorage.getState() == Storage.State.NIL) {
                    mStorage.setState(Storage.State.NIL_2_DOWNLOADING);
                }

                Message message = new Message();
                message.what = SET_TWO_STATE_BUTTON_ENABLED;
                if (DownloadHandler != null) {
                    DownloadHandler.sendMessage(message);
                }

                is = response.getEntity().getContent();
                // Storage Access Framework
                /*bug942627 systemupdate: pause and resume feature can not use*/
                DocumentFile targetDocument = getUpdateDocumentFilePath(mContext, getSdcardRootUri(mContext));
                if (targetDocument != null) {
                    if(targetDocument.length() != 0){
                        out = getContentResolver().openOutputStream(targetDocument.getUri(), "wa");
                }else{
                        out = getContentResolver().openOutputStream(targetDocument.getUri());
                    }
                }
                /*bug942627 systemupdate: pause and resume feature can not use*/

                byte[] buffer = new byte[BUFFER_SIZE];

                int count;
                try {
                    count = is.read(buffer, 0, BUFFER_SIZE);
                } catch (Exception e) {
                    return false;
                }

                while (count != -1) {

                    if (isUpdateFileEffective(total) == false) {
                        updateFileState = false;
                        return false;
                    }
                    out.write(buffer, 0, count);
                    total += count;
                    mStorage.setSize(total);
                    if (total > nextThreshHold
                            || (total - pre_download) >= ONE_TENTH_MB) {
                        pre_download = total;
                        publishProgress(total);
                        nextThreshHold += publishStep;
                    }
                    if (isCancelled()) {

                        pre_download = total;
                        publishProgress(mStorage.getSize());
                        nextThreshHold += publishStep;

                        if (out != null) {
                            out.close();
                            out = null;
                        }
                        if (mInfo.mSize == total) {
                            return true;
                        } else {
                            return false;
                        }
                    }

                    try {
                        count = is.read(buffer, 0, BUFFER_SIZE);
                        if (count == -1 && total < mInfo.mSize) {
                            Log.e(TAG, "network is unreached!!!");
                            return false;
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "getDelta_inputstream.read" + e.toString());
                        return false;
                    }

                }

                if (total == mInfo.mSize) {
                    return true;
                } else {
                    return false;
                }

            } catch (Exception e) {
                Log.e(TAG, "DownloadTask" + e.toString());
                return false;
            } finally {
                try {
                    client.getConnectionManager().shutdown();
                    if (is != null) {
                        is.close();
                    }
                    if (out != null) {
                        out.close();
                        out = null;
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void noticeServer(String notice) throws NoSuchAlgorithmException {
        if (mInfo == null) {
            return;
        }
        final String url = notice;
        String token = mTokenVerification.getToken(TokenVerification.NO_SEED);
        String deviceId = mTokenVerification.mDeviceId;
        String delta_name = mInfo.mDelta_name;
        Log.e(TAG, "noticeServer--token:" + token);
        final List<NameValuePair> pairs = new ArrayList<NameValuePair>();
        pairs.add(new BasicNameValuePair("token", token));
        pairs.add(new BasicNameValuePair("jid", deviceId));
        pairs.add(new BasicNameValuePair("delta_name", delta_name));

        new Thread() {

            @Override
            public void run() {
                try {
                    DefaultHttpClient client = new DefaultHttpClient();
                    HttpPost post = new HttpPost("/request/" + url);
                    post.setEntity(new UrlEncodedFormEntity(pairs));
                    HttpResponse response = client.execute(new HttpHost(
                            PushService.SERVER_ADDR, 3000), post);

                    if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                        Log.e(TAG, "HTTPStatusCode"
                                + response.getStatusLine().getStatusCode());
                    }
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getEntity()
                                    .getContent()));
                    String json = reader.readLine();
                    JSONObject result = new JSONObject(json);
                    int status = result.getInt("status");
                    ErrorStatus
                            .DealStatus(mContext, status, ErrorStatus.NOTICE);
                    Log.e(TAG, "noticeServer--status:" + status);
                } catch (Exception e) {
                    Log.e(TAG, "noticeServer--Exception:" + e.toString());
                    e.printStackTrace();
                }

            }

        }.start();

    }

    private String getTitleAndPercentage(String title) {
        String percentage = mContext.getString(R.string.percentage);
        String downloadPercentage = null;
        if (percentage != null && mStorage.getLatestVersion() != null) {
            downloadPercentage = String.format(percentage,
                    mStorage.getSize() / (mStorage.getLatestVersion().mSize / 100));
        }
        String titleAndPercentage = title + " " + downloadPercentage;
        return titleAndPercentage;
    }

    private boolean isUpdateFileEffective(int size) {
        File file = new File(mStorage.getStorageFilePath());

        if (!file.exists()) {
            return false;
        }

        if (file.length() != size) {
            return false;
        }
        return true;
    }

    /*bug938661 systemupdate can not use when download ota package on app proprietary catalogues*/
    private static Uri getSdcardRootUri(Context context) {
        try {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            String uri = sharedPreferences.getString("storage_file_path", null);

            if (uri != null) {
                return Uri.parse(uri);
            }
        } catch(Throwable e) {
            Log.e(TAG, "getSdcardRootUri error, Throwable: ",e);
        }

        return null;
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

    /*bug modify*/
    public NotificationChannel createNotificationChannel(){
        String id = "systemupdate_channel";
        mSystemUpdateChannel = new NotificationChannel(id, "systemUpdate", NotificationManager.IMPORTANCE_LOW);
        mNotificationManager.createNotificationChannel(mSystemUpdateChannel);
        return mSystemUpdateChannel;
    }
}
