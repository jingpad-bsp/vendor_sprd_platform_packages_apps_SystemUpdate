package com.sprd.systemupdate;

import android.app.Application;

public class DownloadStateApplication extends Application{
    private String mCurrentDownload;

    public String getmCurrentDownload() {
        return mCurrentDownload;
    }

    public void setmCurrentDownload(String mCurrentDownload) {
        this.mCurrentDownload = mCurrentDownload;
    }

}
