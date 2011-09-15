package com.max77.uricache;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.max77.uricache.CachedUriRequest.CacheMode;
import com.max77.uricache.CachedUriRequest.LoaderCallback;
import com.max77.uricache.CachedUriRequest.RequestType;

public class UriCacheActivity extends Activity {
	private static final String URI = "http://www.youhtc.ru/wp-content/uploads/android-logo-white-300x300.png";
	
	private CacheConfiguration mConfig;
	
	private ImageButton mBtn;
	private TextView mTv;
	
	private void testCache(int state) {
		try {
			mBtn.setImageDrawable(getResources().getDrawable(R.drawable.icon));

			CacheMode mode;
			
			switch(state) {
			case 0:
				mode = CacheMode.CACHE_NORMAL;
				break;
			case 1:
				mode = CacheMode.CACHE_FORCE_REFRESH;
				break;
			default:
				mode = CacheMode.CACHE_BYPASS;
				break;
			}
			
			CachedUriRequest rq = new CachedUriRequest(mConfig, RequestType.HTTP_GET, URI, mode);
			rq.setLoadCallback(new LoaderCallback() {
				@Override
				public void onLoadFinished(CachedUriRequest rq) {
					ByteArrayInputStream bais = new ByteArrayInputStream(rq.getServerResponse().toByteArray());
					mBtn.setImageDrawable(Drawable.createFromStream(bais, "src"));
					try {
						bais.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			});
			
			rq.executeAsync();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.main);
        
        mConfig = new CacheConfiguration(getApplicationContext());
        
        mTv = (TextView) findViewById(R.id.textView1);
        mBtn = (ImageButton) findViewById(R.id.imageButton1);
        mBtn.setOnClickListener(new View.OnClickListener() {
        	private int state = 0;
			
			@Override
			public void onClick(View v) {
				if(state > 2)
					state = 0;
				long t1 = System.currentTimeMillis();
				testCache(state ++);
				long t2 = System.currentTimeMillis();
				mTv.setText("time = " + (t2 - t1) + "ms");
			}
		});
    }
}