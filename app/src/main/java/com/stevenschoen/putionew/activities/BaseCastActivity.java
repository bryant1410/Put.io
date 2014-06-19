package com.stevenschoen.putionew.activities;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.common.images.WebImage;
import com.google.sample.castcompanionlibrary.widgets.MiniController;
import com.stevenschoen.putionew.PutioUtils;
import com.stevenschoen.putionew.R;
import com.stevenschoen.putionew.cast.CastService;
import com.stevenschoen.putionew.cast.CastService.CastServiceBinder;
import com.stevenschoen.putionew.model.files.PutioFileData;

import org.apache.commons.io.FilenameUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class BaseCastActivity extends ActionBarActivity implements CastService.CastCallbacks {

    private CastService castService;
	private MiniController castBar;

	private boolean resumed = false;

    @SuppressLint("InlinedApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent castServiceIntent = new Intent(this, CastService.class);
        startService(castServiceIntent);
        bindService(castServiceIntent, castServiceConnection, Service.BIND_ABOVE_CLIENT);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.cast, menu);

        MenuItem buttonMediaRoute = menu.findItem(R.id.menu_cast);
		MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider)
				MenuItemCompat.getActionProvider(buttonMediaRoute);
        if (castService != null) {
            mediaRouteActionProvider.setRouteSelector(castService.getMediaRouteSelector());
        }

        return true;
    }

    protected void initCast() {
        supportInvalidateOptionsMenu();
        castBar = (MiniController) findViewById(R.id.castbar_holder);
		if (castBar != null && castService != null) {
			castService.videoCastManager.setContext(this);
			castService.videoCastManager.addMiniController(castBar);
		}

		if (resumed && castService != null) {
			castService.videoCastManager.incrementUiCounter();
			resumed = false;
		}
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (castService != null && castService.isPlaying()) {
                    castService.videoCastManager.updateVolume(1);
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (castService != null && castService.isPlaying()) {
                    castService.videoCastManager.updateVolume(-1);
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                return castService != null && castService.isPlaying();
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return castService != null && castService.isPlaying();
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (castService != null) {
            castService.videoCastManager.incrementUiCounter();
        } else {
			resumed = true;
		}
    }

    @Override
    protected void onPause() {
        if (castService != null) {
            castService.videoCastManager.decrementUiCounter();
        }
        super.onPause();
    }

    private ServiceConnection castServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            CastServiceBinder binder = (CastServiceBinder) service;
            castService = binder.getService();
            initCast();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            castService = null;
        }
    };

    public void load(PutioFileData file, String url, PutioUtils utils) {
        if (castService == null || !castService.isCasting()) {
            utils.getStreamUrlAndPlay(this, file, url);
        } else {
            MediaMetadata metaData = new MediaMetadata(file.contentType.contains("video") ?
                    MediaMetadata.MEDIA_TYPE_MOVIE : MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
            metaData.putString(MediaMetadata.KEY_TITLE, FilenameUtils.removeExtension(file.name));
            metaData.addImage(new WebImage(Uri.parse(file.icon)));
            metaData.addImage(new WebImage(Uri.parse(file.screenshot)));

            String subtitleUrl = PutioUtils.baseUrl + "files/" + file.id + "/subtitles/default" +
					utils.tokenWithStuff + "&format=webvtt";
			JSONObject customData = null;
			try {
				customData = new JSONObject();
				JSONObject cc = new JSONObject();
				JSONArray tracks = new JSONArray();
				JSONObject src = new JSONObject();
				src.put("src", subtitleUrl);
				tracks.put(src);
				cc.put("tracks", tracks);
				cc.put("active", 0);
				customData.put("cc", cc);
			} catch (JSONException e) {
				e.printStackTrace();
			}
			MediaInfo mediaInfo = new MediaInfo.Builder(url)
                    .setContentType(file.contentType)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setMetadata(metaData)
//					.setCustomData(customData)
                    .build();
            castService.loadAndPlayMedia(mediaInfo, customData);
        }
    }

    protected void onDestroy() {
        if (castService != null) {
            castService.videoCastManager.removeMiniController(castBar);
            unbindService(castServiceConnection);
        }

        super.onDestroy();
    }
}