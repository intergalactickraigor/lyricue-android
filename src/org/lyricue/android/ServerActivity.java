package org.lyricue.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.TextView;

public class ServerActivity extends Activity {
	private static final String TAG = Lyricue.class.getSimpleName();
	ServerSocket ss = null;
	Thread myCommsThread = null;
	public static final int SERVERPORT = 2346;
	protected static final int MSG_ID = 0x1337;
	String mClientMsg = "";
	String server_host = "";
	String profile = "";
	LyricueDisplay ld = null;
	private static JmDNS mJmDNS = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		server_host = getIntent().getStringExtra("host");
		profile = getIntent().getStringExtra("profile");
		new FindDisplayTask().execute();
		setContentView(R.layout.server);
		TextView tv = (TextView) findViewById(R.id.textServerFooter);
		tv.setText("Nothing from client yet");
		tv = (TextView) findViewById(R.id.textServerMain);
		tv.setText("");
		tv = (TextView) findViewById(R.id.textServerHeader);
		tv.setText("");
		this.myCommsThread = new Thread(new CommsThread());
		this.myCommsThread.start();
	}

	@Override
	protected void onStop() {
		super.onStop();
		try {
			// make sure you close the socket upon exiting
			if (ss != null)
				ss.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.myCommsThread.interrupt();
	}

	Handler myUpdateHandler = new Handler() {
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_ID:
				if (mClientMsg != null) {
					if (mClientMsg.startsWith("android", 0)) {
						TextView tv = (TextView) findViewById(R.id.textServerMain);
						tv.setText(mClientMsg);
					} else if (mClientMsg.startsWith("get", 0)) {
						// ignore
					} else {
						new CheckRemoteTask().execute();
					}
				}
				break;
			default:
				break;
			}
			super.handleMessage(msg);
		}
	};

	private class FindDisplayTask extends AsyncTask<Void, Void, String> {
		protected String doInBackground(Void... arg0) {
			ld = new LyricueDisplay(server_host);
			if (server_host.equals("#demo")) {
				return null;
			}
			String my_server_host = ld
					.runQuery_string(
							"lyricDb",
							"SELECT host FROM status WHERE TIMEDIFF(NOW(), lastupdate) < '00:00:02' AND type='headless' AND profile='"
									+ profile + "'", "host");
			if (my_server_host == null || my_server_host.equals("")) {
				my_server_host = ld
						.runQuery_string(
								"lyricDb",
								"SELECT host FROM status WHERE TIMEDIFF(NOW(), lastupdate) < '00:00:02' AND type='normal' AND profile='"
										+ profile + "'", "host");
			}
			if (my_server_host == null || my_server_host.equals("")) {
				my_server_host = ld
						.runQuery_string(
								"lyricDb",
								"SELECT host FROM status WHERE TIMEDIFF(NOW(), lastupdate) < '00:00:02' AND type='simple' AND profile='"
										+ profile + "'", "host");
			}
			if (my_server_host == null || my_server_host.equals("")) {
				Log.i(TAG, "No suitable hosts found");
				finish();
			}
			if (android.os.Build.MODEL.equals("google_sdk")
					|| android.os.Build.MODEL.equals("sdk")) {
				Log.d(TAG, "Server host:" + my_server_host);
				String[] values = my_server_host.split(":");
				my_server_host = "10.0.2.2:" + values[1];
				Log.i(TAG, "Adding host:" + my_server_host);
			}
			
			return my_server_host;
		}
		
		protected void onPostExecute(String host) {
			server_host=host;
			ld = new LyricueDisplay(server_host);
			new CheckRemoteTask().execute();
		}

	}

	private class CheckRemoteTask extends AsyncTask<Void, Void, String[]> {
		protected String[] doInBackground(Void... arg0) {
			String[] retval = new String[4];
			retval[0] = ld.runCommand(0, "get", "header", "");
			retval[1] = ld.runCommand(0, "get", "main", "");
			retval[2] = ld.runCommand(0, "get", "footer", "");
			return retval;
		}

		protected void onPostExecute(String[] values) {
			TextView tv = (TextView) findViewById(R.id.textServerHeader);
			tv.setText(values[0]);
			tv = (TextView) findViewById(R.id.textServerMain);
			tv.setText(values[1]);
			tv = (TextView) findViewById(R.id.textServerFooter);
			tv.setText(values[2]);
		}
	}

	class CommsThread implements Runnable {
		public void run() {
			Map<String, String> params = new HashMap<String, String>();
			params.put("type", "android");
			params.put("profile", profile);
			params.put("data", server_host);
			ServiceInfo mServiceInfo = ServiceInfo.create(
					"_lyricue._tcp.local.", "Lyricue Display", SERVERPORT, 1,
					1, params);
			try {
				WifiManager wifi = (WifiManager) ServerActivity.this
						.getSystemService(Context.WIFI_SERVICE);
				WifiInfo wifiinfo = wifi.getConnectionInfo();
				int intaddr = wifiinfo.getIpAddress();
				byte[] byteaddr = new byte[] { (byte) (intaddr & 0xff),
						(byte) (intaddr >> 8 & 0xff),
						(byte) (intaddr >> 16 & 0xff),
						(byte) (intaddr >> 24 & 0xff) };

				mJmDNS = JmDNS.create(InetAddress.getByAddress(byteaddr));

				mJmDNS.registerService(mServiceInfo);
				Log.w(TAG,
						String.format("registerService:",
								mServiceInfo.toString()));
			} catch (Exception e) {

				e.printStackTrace();
			}
			Socket s = null;
			try {
				ss = new ServerSocket(SERVERPORT);
			} catch (IOException e) {
				e.printStackTrace();
			}
			if (ss== null){
				finish();
			}
			while (!Thread.currentThread().isInterrupted()) {
				Message m = new Message();
				m.what = MSG_ID;
				try {
					if (s == null)
						s = ss.accept();
					BufferedReader input = new BufferedReader(
							new InputStreamReader(s.getInputStream()));
					String st = null;
					st = input.readLine();
					mClientMsg = st;
					if (mClientMsg != null) {
						myUpdateHandler.sendMessage(m);
					}
					s.close();
					s = null;
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			mJmDNS.unregisterService(mServiceInfo);
			Log.i(TAG, String.format("unregisterService:",
					mServiceInfo.toString()));
			try {
				mJmDNS.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
