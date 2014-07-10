/*

 ** Copyright (C) 2013 Mellanox Technologies
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at:
 **
 ** http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 ** either express or implied. See the License for the specific language
 ** governing permissions and  limitations under the License.
 **
 */
package com.mellanox.jxio.jxioConnection.impl;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.mellanox.jxio.EventName;
import com.mellanox.jxio.EventQueueHandler;
import com.mellanox.jxio.EventReason;
import com.mellanox.jxio.Msg;
import com.mellanox.jxio.MsgPool;
import com.mellanox.jxio.ServerPortal;
import com.mellanox.jxio.ServerSession;
import com.mellanox.jxio.jxioConnection.JxioConnectionServer;
import com.mellanox.jxio.jxioConnection.JxioConnectionServer.Callbacks;

public class ServerWorker extends Thread implements BufferSupplier {

	private final static Log                     LOG            = LogFactory.getLog(ServerWorker.class
	                                                                    .getCanonicalName());
	private final ServerPortal                   sp;
	private final EventQueueHandler              eqh;
	public final int                             portalIndex;
	private final SessionServerCallbacks         callbacks;
	private final JxioConnectionServer.Callbacks appCallbacks;
	private final String                         name;
	private ArrayList<MsgPool>                   msgPools;
	private boolean                              sessionClosed  = false;
	private boolean                              waitingToClose = false;
	private Msg                                  msg            = null;
	private int                                  count          = 0;
	private ServerSession                        session        = null;
	private boolean                              stop           = false;
	private URI                                  uri            = null;
	private boolean                              firstMsg       = true;
	private StreamWorker                         streamWorker;

	/**
	 * CTOR of a server worker, each worker is connected to only 1 client at a time
	 * 
	 * @param index
	 *            - used as worker id
	 * @param uri
	 *            - uri from client, is used also to pass data between client and server
	 * @param numMsgs
	 *            - number of msgs in msgpool
	 * @param appCallbacks
	 *            - callbacks that are called when a new session is started
	 */
	public ServerWorker(int index, URI uri, JxioConnectionServer.Callbacks appCallbacks) {
		portalIndex = index;
		name = "[ServerWorker " + portalIndex + " ]";
		eqh = new EventQueueHandler(new EqhCallbacks(JxioConnectionServer.msgPoolnumMsgs,
		        JxioConnectionServer.msgPoolBuffSize, JxioConnectionServer.msgPoolBuffSize));
		this.msgPools = new ArrayList<MsgPool>();
		MsgPool pool = new MsgPool(JxioConnectionServer.msgPoolnumMsgs, JxioConnectionServer.msgPoolBuffSize,
		        JxioConnectionServer.msgPoolBuffSize);
		msgPools.add(pool);
		eqh.bindMsgPool(pool);
		sp = new ServerPortal(eqh, uri);
		callbacks = new SessionServerCallbacks();
		this.appCallbacks = appCallbacks;
		LOG.info(this.toString() + " is up and waiting for requests");
	}

	/**
	 * Main loop of worker thread.
	 * waits in eqh until first msgs is recieved
	 */
	public void run() {
		while (!stop) {
			LOG.info(this.toString()+" waiting for a new connection");
			eqh.runEventLoop(1, -1); // to get the forward going
			streamWorker.callUserCallback(uri);
			close();
		}
	}

	private String getStreamType() {
		String[] queries = uri.getQuery().split("&");
		return queries[0].split("stream=")[1];
	}

	/**
	 * This function is called just before the server listener forwards a new connection to the worker
	 * Reset all variables
	 * 
	 * @param ss
	 *            - the new session that was constructod for this connection
	 * @param sk
	 *            - session key to get the uri
	 */
	public void prepareSession(ServerSession ss, URI uri) {
		session = ss;
		sessionClosed = false;
		waitingToClose = false;
		msg = null;
		count = 0;
		this.uri = uri;
		firstMsg = true;
		String type = getStreamType();
		if (type.compareTo("input") == 0) {
			streamWorker = new OSWorker(this, appCallbacks);
		} else if (type.compareTo("output") == 0) {
			streamWorker = new ISWorker(this, appCallbacks);
		} else {
			throw new UnsupportedOperationException("Stream type is not recognized");
		}
	}

	public ServerPortal getPortal() {
		return sp;
	}

	public SessionServerCallbacks getSessionCallbacks() {
		return callbacks;
	}

	/**
	 * clears the session and return worker to pool
	 */
	private void sessionClosed() {
		LOG.info(this.toString() + " disconnected from a Session");
		session = null;
		JxioConnectionServer.updateWorkers(this);
	}

	public EventQueueHandler getEqh() {
		return eqh;
	}

	/**
	 * Called from MultiBuffInputStream when an empty buffer is needed.
	 * Send the previous msg (that now it's buffer is full - since we requested an empty one)
	 * wait on eqh until a new msg is recieved
	 */
	public ByteBuffer getNextBuffer() throws IOException {
		if (!firstMsg || msg == null) {
			sendMsg();
			do {
				eqh.runEventLoop(1, -1);
			} while (!sessionClosed && msg == null);
			if (sessionClosed) {
				throw new IOException("Session was closed, no buffer avaliable");
			}
		}
		firstMsg = false;
		return streamWorker.getMsgBuffer(msg);
	}

	public void sendMsg() {
		if (msg != null) {
			count++;
			session.sendResponse(msg);
			msg = null;
		}
	}

	/**
	 * Send msg even if it's buffers is not full
	 */
	public void flush() {
		if (streamWorker.canFlush()) {
			sendMsg();
		} else {
			throw new UnsupportedOperationException("flush is not supported");
		}
	}

	/**
	 * Close the session and wait until all msgs are returned to the msgpoll
	 */
	public void close() {
		if (waitingToClose || session == null)
			return;
		sendMsg(); //free last msg if needed
		LOG.info(this.toString() + " closing session sent " + count + " msgs");
		waitingToClose = true;
		session.close();
		while (!sessionClosed) {
			eqh.runEventLoop(-1, -1);
		}
		sessionClosed();
	}

	/**
	 * Session Callbacks
	 * 
	 */
	public class SessionServerCallbacks implements ServerSession.Callbacks {
		public void onRequest(Msg m) {
			msg = m;
			if (waitingToClose) {
				session.discardRequest(m);
			}
		}

		public void onSessionEvent(EventName session_event, EventReason reason) {
			LOG.info(ServerWorker.this.toString() + " got event " + session_event.toString() + ", the reason is "
			        + reason.toString());
			if (session_event == EventName.SESSION_CLOSED) {
				sessionClosed = true;
				waitingToClose = true;
				eqh.breakEventLoop();
			}
		}

		public boolean onMsgError(Msg msg, EventReason reason) {
			LOG.error(this.toString() + " onMsgErrorCallback. reason is " + reason);
			return true;
		}
	}

	class EqhCallbacks implements EventQueueHandler.Callbacks {
		private final ServerWorker outer = ServerWorker.this;
		private final int          numMsgs;
		private final int          inMsgSize;
		private final int          outMsgSize;

		public EqhCallbacks(int msgs, int in, int out) {
			numMsgs = msgs;
			inMsgSize = in;
			outMsgSize = out;
		}

		public MsgPool getAdditionalMsgPool(int in, int out) {
			MsgPool mp = new MsgPool(numMsgs, inMsgSize, outMsgSize);
			LOG.warn(this.toString() + " " + outer.toString() + ": new MsgPool: " + mp);
			outer.msgPools.add(mp);
			return mp;
		}

	}

	public String toString() {
		return this.name;
	}

	private class OSWorker implements StreamWorker {

		private ServerWorker worker;
		private Callbacks    appCallbacks;

		public OSWorker(ServerWorker worker, Callbacks appCallbacks) {
			this.worker = worker;
			this.appCallbacks = appCallbacks;
		}

		public ByteBuffer getMsgBuffer(Msg m) {
			return m.getOut();
		}

		public void callUserCallback(URI uri) {
			appCallbacks.newSessionOS(uri, new MultiBufOutputStream(worker));
		}

		public boolean canFlush() {
			return true;
		}
	}

	private class ISWorker implements StreamWorker {

		private ServerWorker worker;
		private Callbacks    appCallbacks;

		public ISWorker(ServerWorker worker, Callbacks appCallbacks) {
			this.worker = worker;
			this.appCallbacks = appCallbacks;
		}

		public ByteBuffer getMsgBuffer(Msg m) {
			return m.getIn();
		}

		public void callUserCallback(URI uri) {
			appCallbacks.newSessionIS(uri, new MultiBuffInputStream(worker));
		}

		public boolean canFlush() {
			return false;
		}
	}

	private interface StreamWorker {
		public ByteBuffer getMsgBuffer(Msg m);

		public boolean canFlush();

		public void callUserCallback(URI uri);
	}
}