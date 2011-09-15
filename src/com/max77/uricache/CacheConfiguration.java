package com.max77.uricache;

import java.io.File;

import android.content.Context;

public class CacheConfiguration {
	// TODO: избавиться ??
	private Context mContext;

	/** Директория, где лежат кэш-файлы */
	private File mCacheDir;
	
	/** Кодировка, используемая при формировании HTTP-запроса */
	private String mEncoding;
	
	/** Максимальное "время жизни" файла в кэше (ms). По умолчанию - 30 суток. */
	private long mMaxFileAge;
	
	/** Таймаут (ms). По умолчанию - 2 минуты. */
	private int mHttpTimeout;
	
	/***************************************************************************/

	public CacheConfiguration(Context context) {
		this(context, "cache", "UTF-8", 86400L * 30 * 1000, 120 * 1000);
	}

	public CacheConfiguration(Context context, String dir, String encoding, long maxFileAge, int httpTimeout) {
		mContext = context;
		mCacheDir = mContext.getDir(dir, Context.MODE_PRIVATE);
		mEncoding = encoding;
		mMaxFileAge = maxFileAge;
		mHttpTimeout = httpTimeout;
	}

	public File getCacheDir() {
		return mCacheDir;
	}

	public String getEncoding() {
		return mEncoding;
	}

	public long getMaxFileAge() {
		return mMaxFileAge;
	}

	public int getHttpTimeout() {
		return mHttpTimeout;
	}
}
