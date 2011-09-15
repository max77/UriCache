package com.max77.uricache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;

import android.os.AsyncTask;
import android.util.Log;

/**
 * HTTP запрос с кэшированием. Синхронный или аснихронный.
 * Кэширование производится средствами файловой системы.
 * @author max77
 */
public class CachedUriRequest {
	private static final String TAG = CachedUriRequest.class.getSimpleName();
	
	/** Тип HTTP-запроса */
	public enum RequestType {
		HTTP_GET,
		HTTP_POST
	}
	
	/** Режим кэширования */
	public enum CacheMode {
		CACHE_NORMAL,
		CACHE_FORCE_REFRESH,
		CACHE_BYPASS
	}

	// TODO: задокументировать это говно
	private CacheConfiguration mConfig;
	private RequestType mRqType;
	private String mUri; 
	private List<NameValuePair> mHeader;
	private List<NameValuePair> mPostParams;
	private String mRawEntity;
	private CacheMode mCacheMode;

	/** Возможные состояния загрузки */
	public enum LoadState {
		NEW, LOADING, LOADED;
	}
	
	/** Текущее состояние объекта */
	private LoadState mLoadState = LoadState.NEW;
	
	/** Исключение, возникшее при загрузке или null */
	private Exception mException = null;
	
	/** Ответ сервера */
	protected ByteArrayOutputStream mServerResponse = null;
	
	/**
	 * Флаг "отложенного" callback'а.
	 * Кагбэ очередь событий из 1-го элемента ;-)
	 */
	private boolean isCallbackDelayed = false;

	/** Собсна загрузчик */
	private AsyncTask<Void, Void, Void> mLoaderTask = null;

	/** Внешний интерфейс */
	public interface LoaderCallback {
		void onLoadFinished(CachedUriRequest rq);
	}

	/** Callback для взаимодействия с внешним миром */
	private LoaderCallback mCallback = null;
	
	/***************************************************************************/

    private void check() {
		if(mUri == null)
			throw new IllegalStateException(TAG + " : URI must be defined");
		if(mConfig == null)
			throw new IllegalStateException(TAG + " : Cache configuration must be set");
    }
	
	/**
     * Генерация уникального (надеюсь) имени файла для кэширования результатов запроса
     * @return String
     */
    public String generateCacheFileName() {
    	check();
    	String fname = Integer.toHexString(mUri.hashCode()) + Integer.toHexString(mRqType.ordinal());
		
		long h = 0;
		if(mPostParams != null) {
			for (NameValuePair pair : mPostParams) {
				h += pair.getName().hashCode();
				h += pair.getValue().hashCode();
			}
			
			fname += Long.toHexString(h);
		}

		h = 0;
		if(mHeader != null) {
			for (NameValuePair pair : mHeader) {
				h += pair.getName().hashCode();
				h += pair.getValue().hashCode();
			}
		
			fname += Long.toHexString(h);
		}
		
		if(mRawEntity != null)
			fname += Integer.toHexString(mRawEntity.hashCode());
		
		return fname;
    }

	public static ArrayList<NameValuePair> prepareNVPList(String... nvp) {
		ArrayList<NameValuePair> nvplist = new ArrayList<NameValuePair>();
			
		for(int i = 0; i < (nvp.length / 2) * 2 - 1; i += 2)
			nvplist.add(new BasicNameValuePair(nvp[i], nvp[i + 1]));
	
		return nvplist;
	}

    public CachedUriRequest(CacheConfiguration config, RequestType rqType, String uri, List<NameValuePair> header,
			List<NameValuePair> postParams, String rawEntity, CacheMode cacheMode) {
		mConfig = config;
		mRqType = rqType;
		mUri = uri;
		mHeader = header;
		mPostParams = postParams;
		mRawEntity = rawEntity;
		mCacheMode = cacheMode;
		mLoadState = LoadState.NEW;
	}

    public CachedUriRequest(CacheConfiguration config, RequestType rqType, String uri, CacheMode cacheMode) {
		this(config, rqType, uri, null, null, null, cacheMode);
	}

	public LoadState getLoadState() {
		return mLoadState;
	}

	public Exception getException() {
		return mException;
	}

	public ByteArrayOutputStream getServerResponse() {
		return mServerResponse;
	}
	
	/**
	 * Если имеется "задержанный" callback, вызвать его немедленно.
	 * 
	 * Хинт: чтобы сбросить "задержанный" callback, 
	 * нужно вызвать setLoadCallback(null) ДВА РАЗА
	 */
	public synchronized void setLoadCallback(LoaderCallback lcb) {
		mCallback = lcb;
		
		if(isCallbackDelayed) {
			if(mCallback != null) 
				mCallback.onLoadFinished(this);
			isCallbackDelayed = false;
		}
	}
	
	/**
	 * Обработка загруженных данных. Переопределяется в дочерних классах.
	 */
	protected void process() throws Exception {}
	
	/**
	 * Переопределяется в дочерних классах.
	 * @return Догадайтесь...
	 */
	public boolean isValid() {
		return true;
	}
	
	public void cancel() {
		if(mLoaderTask != null)
			mLoaderTask.cancel(true);
	}
	
    /***************************************************************************/

	private byte[] mBuf = new byte[8192];
	
	private ByteArrayOutputStream is2baos(InputStream is) throws IOException {
	    int len;
	    BufferedInputStream bis = new BufferedInputStream(is);
	    ByteArrayOutputStream baos = new ByteArrayOutputStream();
	    
	    while ((len = bis.read(mBuf)) > 0) {
	        baos.write(mBuf, 0, len);
	    }
	    
	    bis.close();
	    
	    return baos;
	}
	
    /**
	 * СИНХРОННОЕ выполнение запроса.
	 */
	public synchronized void executeSync() {
		check();

		if(mLoadState != LoadState.NEW)
			throw new IllegalStateException(TAG + " : can execute request only once !");
		
		mLoadState = LoadState.LOADING;
		
		try {
			// DEBUG BEGIN
			StringBuilder sb = new StringBuilder();
			sb.append("--------------------------------------------------\n");
			
			sb.append("*****URI*****").append(mUri).append("\n");
	
			if(mHeader != null) {
				sb.append("*****HTTP HEADER*****").append(mUri).append("\n");
			
				for (NameValuePair nameValuePair : mHeader)
					sb.append("+++++++").append(nameValuePair.getName()).append("\n").append(nameValuePair.getValue()).append("\n");
			}
	
			if(mPostParams != null) {
				sb.append("*****POST PARAMS*****").append(mUri).append("\n");
			
				for (NameValuePair nameValuePair : mPostParams)
					sb.append("+++++++").append(nameValuePair.getName()).append("\n").append(nameValuePair.getValue()).append("\n");
			}
			
			if(mRawEntity != null) {
				sb.append("*****RAW PARAMS*****").append(mUri).append("\n");
				sb.append("+++++++").append(mRawEntity).append("\n");
			}
			
			sb.append("*****CACHING*****").append(mUri).append("\n");
			sb.append(mCacheMode.toString());
			
			Log.d(TAG, sb.toString());
			// DEBUG END
			
			// Проверяем наличие результатов запроса в кэше
			File file = null;

			if(mCacheMode != CacheMode.CACHE_BYPASS) {
				file = new File(mConfig.getCacheDir(), generateCacheFileName());
		   		
				// Обычный режим кэширования
				if(mCacheMode == CacheMode.CACHE_NORMAL && file.exists() && (System.currentTimeMillis() - file.lastModified() < mConfig.getMaxFileAge())) {
			   		Log.d(TAG, "Using existing cache file: " + file.getPath());
		   			mServerResponse = is2baos(new FileInputStream(file));
		   		}
		   		// Режим принудительного обновления или данные еще не закэшированы или кэш-файл есть, но устарел 
				else { 
	   				file.delete();
			   		file.createNewFile();
			   		Log.d(TAG, "Created cache file: " + file.getPath());
		   		}
			}
			
			// В кэше инфы нет - переходим к HTTP-запросу
			if(mServerResponse == null) {
				HttpParams params = new BasicHttpParams();  
		//        params.setParameter(ConnManagerPNames.MAX_TOTAL_CONNECTIONS, 5);  
		//        params.setParameter(ConnManagerPNames.MAX_CONNECTIONS_PER_ROUTE, new ConnPerRouteBean(5));
		        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);  
		
		        // Установка таймаута
		        HttpConnectionParams.setStaleCheckingEnabled(params, true);
		        HttpConnectionParams.setConnectionTimeout(params, mConfig.getHttpTimeout());
		        HttpConnectionParams.setSoTimeout(params, mConfig.getHttpTimeout());
		        HttpConnectionParams.setSocketBufferSize(params, 8192);
		  
		        HttpClient cli = new DefaultHttpClient(params);
		
		        HttpRequestBase rq;
		        
		        switch(mRqType) {
		        case HTTP_GET:
		        	rq = new HttpGet(mUri);
		        	break;
		        case HTTP_POST:
		        	rq = new HttpPost(mUri);
		    		((HttpPost) rq).setEntity(mRawEntity != null ? 
		    				new StringEntity(mRawEntity, mConfig.getEncoding()) : 
		    					new UrlEncodedFormEntity(mPostParams, mConfig.getEncoding()));
		        	break;
		        	default:
		        		throw new IllegalStateException(TAG + " : HTTP request type must be either HTTP_GET or HTTP_POST");
		        }
		        
		        if(mHeader != null)
		        	for (NameValuePair nvp : mHeader) {
						rq.addHeader(nvp.getName(), nvp.getValue());
					}
		        
				HttpResponse response = cli.execute(rq);
		   
				int code = response.getStatusLine().getStatusCode();
				if(code != HttpStatus.SC_OK)
					throw new Exception(String.format("%s: HTTP Error %d !!!", mUri, code));
					
				// Получаем результат запроса
				mServerResponse = is2baos(response.getEntity().getContent());
		
				// Если кэширование не запрещено, записываем результат запроса в ранее созданный файл
				if(mCacheMode != CacheMode.CACHE_BYPASS) {
					FileOutputStream fileOut = new FileOutputStream(file);
					BufferedOutputStream out = new BufferedOutputStream(fileOut);

					out.write(mServerResponse.toByteArray());
					out.close();
					
				    Log.d(TAG, "cache file updated: " + file.getPath());
				}
			}
	
			// DEBUG BEGIN
//			sb = new StringBuilder();
//			sb.append("******SERVER RESPONSE******\n");
//			Scanner s = new Scanner(mServerResponse.toString(mConfig.getEncoding()));
//			String line;
//			try {
//				while((line = s.nextLine()) != null)
//					sb.append("\"" + line + "\"\n");
//			} catch (NoSuchElementException e) { } 
//			Log.d(TAG, sb.toString());
			// DEBUG END
	
			process();
			mLoadState = LoadState.LOADED; 
		} catch (Exception e) {
			Log.e(TAG, "Error: " + e.getMessage());
			mException = e;
		}
	}

	public synchronized void executeAsync() {
		mLoaderTask = new AsyncTask<Void, Void, Void>() {
			// Фоновая загрузка и парсинг
			@Override
			protected Void doInBackground(Void... params) {
				executeSync();
				return null;
			}

			// Callback. Вызывается в UI-потоке после загрузки
			protected void onPostExecute(Void result) {
				mLoadState = LoadState.LOADED;

				if(mCallback != null)
					mCallback.onLoadFinished(CachedUriRequest.this);
				else
					isCallbackDelayed = true;
			}

		};
		
		mLoaderTask.execute();
	}

	//	/**
//     * Чистит директорию от старых файлов. 
//     * @return количество удаленных файлов
//     */
//    public int purge() {
//    	int count = 0;
//    	long millis = System.currentTimeMillis();
//    	
//    	for (File f : mConfig.mCacheDir.listFiles()) {
//			if(millis - f.lastModified() > mConfig.mMaxFileAge) {
//				f.delete();
//				count ++;
//				
//				Log.d(TAG, "old cache file deleted: " + f.getAbsolutePath());
//			}
//		}
//    	
//    	return count;
//    }
}
