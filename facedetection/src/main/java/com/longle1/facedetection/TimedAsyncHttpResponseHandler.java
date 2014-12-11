/*******************************************************************************
 * Copyright (c) 2014 University of Illinois at Urbana-Champaign.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Long Le, David Jun, and Douglas Jones - initial API and implementation
 *******************************************************************************/
package com.longle1.facedetection;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.security.KeyStore;

import org.apache.http.Header;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.json.JSONObject;

import android.content.Context;
import android.graphics.Color;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import com.longle1.facedetection.R;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

public class TimedAsyncHttpResponseHandler extends AsyncHttpResponseHandler{
	long startTime;
	Context mContext;
	
	public TimedAsyncHttpResponseHandler(Looper asyncHttpLooper, Context context){
		super(asyncHttpLooper); // use a custom looper for async handler
		startTime = System.nanoTime();
		mContext = context; // context to get resources
	}
	
	@Override
	public void onFailure(int statusCode, Header[] headers, byte[] responseBody,
			Throwable error) {
		Log.e("RTT: ", String.format("%.1f", (System.nanoTime()-startTime)/1e6)+" ms");
		String msg = "RTT: "+ String.format("%.1f", (System.nanoTime()-startTime)/1e6)+" ms";
		Toast mToast = Toast.makeText(mContext, msg, Toast.LENGTH_SHORT);
		mToast.setGravity(Gravity.TOP, 0, 0);
		TextView v = (TextView) mToast.getView().findViewById(android.R.id.message);
		v.setTextColor(Color.RED);
		mToast.show();
	}
	@Override
	public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
		Log.i("RTT: ", String.format("%.1f", (System.nanoTime()-startTime)/1e6)+" ms");
		String msg = "RTT: "+ String.format("%.1f", (System.nanoTime()-startTime)/1e6)+" ms";
		Toast mToast = Toast.makeText(mContext, msg, Toast.LENGTH_SHORT);
        mToast.setGravity(Gravity.TOP, 0, 0);
		mToast.show();
	}

	public void executePut(String putURL, RequestParams params,JSONObject json) {
		try {
			AsyncHttpClient client = new AsyncHttpClient();
			StringEntity se = null;
			try{
		        se = new StringEntity( json.toString());  
			}catch (UnsupportedEncodingException e) {
				e.printStackTrace();
				return;
	    	}
			se.setContentType(new BasicHeader(HTTP.CONTENT_TYPE, "application/json"));
			
			// Add SSL
	        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
	        trustStore.load(mContext.getResources().openRawResource(R.raw.truststore), "changeit".toCharArray());
	        SSLSocketFactory sf = new SSLSocketFactory(trustStore);
	        client.setSSLSocketFactory(sf);
			
	        client.setTimeout(30000);
	        
	        client.put(null, putURL+"?"+params.toString(), se, null, 
	        		this);
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
		Log.i("executePut", "done");
	}
	
	public void executePut(String putURL, RequestParams params, byte[] bb) {
		try {
			AsyncHttpClient client = new AsyncHttpClient();
			ByteArrayEntity bae = null;
	        bae = new ByteArrayEntity(bb);  
			bae.setContentType(new BasicHeader(HTTP.CONTENT_TYPE, "application/octet-stream"));
			
			// Add SSL
	        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
	        trustStore.load(mContext.getResources().openRawResource(R.raw.truststore), "changeit".toCharArray());
	        SSLSocketFactory sf = new SSLSocketFactory(trustStore);
	        client.setSSLSocketFactory(sf);
			
	        client.setTimeout(30000);
	        
	        client.put(null, putURL+"?"+params.toString(), bae, null, 
	        		this);
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
		Log.i("executePut", "done");
	}
	
	public void executePut(String putURL, RequestParams params,String filename){
		try {
			AsyncHttpClient client = new AsyncHttpClient();
			FileEntity fe = null;
	        fe = new FileEntity( new File(filename), "audio/wav");  
	        
	        // Add SSL
	        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
	        trustStore.load(mContext.getResources().openRawResource(R.raw.truststore), "changeit".toCharArray());
	        SSLSocketFactory sf = new SSLSocketFactory(trustStore);
	        client.setSSLSocketFactory(sf);
			
	        client.setTimeout(30000);
	        
	        client.put(null, putURL+"?"+params.toString(), fe, null, 
	        		this);
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
		Log.i("executePut", "done");
	}
}