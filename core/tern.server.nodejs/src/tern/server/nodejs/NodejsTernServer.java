/**
 *  Copyright (c) 2013-2014 Angelo ZERR.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package tern.server.nodejs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import tern.ITernProject;
import tern.TernException;
import tern.TernResourcesManager;
import tern.server.AbstractTernServer;
import tern.server.IInterceptor;
import tern.server.IResponseHandler;
import tern.server.ITernDef;
import tern.server.ITernPlugin;
import tern.server.nodejs.process.INodejsProcessListener;
import tern.server.nodejs.process.NodejsProcess;
import tern.server.nodejs.process.NodejsProcessAdapter;
import tern.server.nodejs.process.NodejsProcessException;
import tern.server.nodejs.process.NodejsProcessManager;
import tern.server.protocol.JsonHelper;
import tern.server.protocol.TernDoc;
import tern.server.protocol.completions.ITernCompletionCollector;
import tern.server.protocol.definition.ITernDefinitionCollector;
import tern.server.protocol.guesstypes.ITernGuessTypesCollector;
import tern.server.protocol.html.ScriptTagRegion;
import tern.server.protocol.lint.ITernLintCollector;
import tern.server.protocol.lint.TernLintQuery;
import tern.server.protocol.type.ITernTypeCollector;
import tern.utils.StringUtils;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

/**
 * Tern server implemented with node.js
 * 
 */
public class NodejsTernServer extends AbstractTernServer {

	private String baseURL;

	private List<IInterceptor> interceptors;

	private NodejsProcess process;
	private List<INodejsProcessListener> listeners;

	private long timeout = NodejsTernHelper.DEFAULT_TIMEOUT;

	private int testNumber = NodejsTernHelper.DEFAULT_TEST_NUMBER;

	private final INodejsProcessListener listener = new NodejsProcessAdapter() {

		@Override
		public void onStart(NodejsProcess server) {
			NodejsTernServer.this.fireStartServer();
		}

		@Override
		public void onStop(NodejsProcess server) {
			dispose();
			fireEndServer();
		}

	};

	private boolean persistent;

	public NodejsTernServer(File projectDir, int port) {
		this(TernResourcesManager.getTernProject(projectDir), port);
	}

	public NodejsTernServer(ITernProject project, int port) {
		super(project);
		this.baseURL = computeBaseURL(port);
	}

	public NodejsTernServer(ITernProject project) throws TernException {
		this(project, NodejsProcessManager.getInstance().create(
				project.getProjectDir()));
	}

	public NodejsTernServer(ITernProject project, File nodejsBaseDir)
			throws TernException {
		this(project, NodejsProcessManager.getInstance().create(
				project.getProjectDir(), nodejsBaseDir));
	}

	public NodejsTernServer(ITernProject project, File nodejsBaseDir,
			File nodejsTernBaseDir) throws TernException {
		this(project, NodejsProcessManager.getInstance().create(
				project.getProjectDir(), nodejsBaseDir, nodejsTernBaseDir));
	}

	public NodejsTernServer(ITernProject project, NodejsProcess process) {
		super(project);
		this.process = process;
		process.addProcessListener(listener);
		initProcess(process);
	}

	private String computeBaseURL(Integer port) {
		return new StringBuilder("http://localhost:").append(port).append("/")
				.toString();
	}

	@Override
	public void addDef(ITernDef def) throws TernException {
		ITernProject project = getProject();
		project.addLib(def);
		try {
			project.save();
		} catch (IOException e) {
			throw new TernException(e);
		}
	}

	@Override
	public void addPlugin(ITernPlugin plugin) throws TernException {
		ITernProject project = getProject();
		project.addPlugin(plugin);
		try {
			project.save();
		} catch (IOException e) {
			throw new TernException(e);
		}
	}

	@Override
	public void addFile(String name, String text, ScriptTagRegion[] tags) {
		TernDoc t = new TernDoc();
		t.addFile(name, text, tags, null);
		try {
			JsonObject json = makeRequest(t);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	@Override
	public void request(TernDoc doc, IResponseHandler handler) {
		try {
			JsonObject json = makeRequest(doc);
			handler.onSuccess(json,
					handler.isDataAsJsonString() ? json.toString() : null);
		} catch (Exception e) {
			handler.onError(e.getMessage(), e);
		}
	}

	private JsonObject makeRequest(TernDoc doc) throws IOException,
			InterruptedException, TernException {
		String baseURL = null;
		try {
			baseURL = getBaseURL();
		} catch (NodejsProcessException e) {
			// the nodejs process cannot start => not a valid node path, dispose
			// the server.
			dispose();
			throw e;
		}

		JsonObject json = NodejsTernHelper.makeRequest(baseURL, doc, false,
				interceptors, this);
		return json;
	}

	public void addInterceptor(IInterceptor interceptor) {
		if (interceptors == null) {
			interceptors = new ArrayList<IInterceptor>();
		}
		interceptors.add(interceptor);
	}

	public void removeInterceptor(IInterceptor interceptor) {
		if (interceptors != null) {
			interceptors.remove(interceptor);
		}
	}

	public String getBaseURL() throws InterruptedException, TernException {
		if (baseURL == null) {
			int port = getProcess().start(timeout, testNumber);
			this.baseURL = computeBaseURL(port);
		}
		return baseURL;
	}

	private NodejsProcess getProcess() throws TernException {
		if (process == null) {
			ITernProject project = super.getProject();
			process = NodejsProcessManager.getInstance().create(
					project.getProjectDir());
			process.addProcessListener(listener);
		}
		initProcess(process);
		return process;
	}

	private void initProcess(NodejsProcess process) {
		process.setPersistent(persistent);
		process.setLoadingLocalPlugins(isLoadingLocalPlugins());
	}

	public void addProcessListener(INodejsProcessListener listener) {
		if (listeners == null) {
			listeners = new ArrayList<INodejsProcessListener>();
		}
		listeners.add(listener);
		if (process != null) {
			process.addProcessListener(listener);
		}
	}

	public void removeProcessListener(INodejsProcessListener listener) {
		if (listeners != null && listener != null) {
			listeners.remove(listener);
		}
		if (process != null) {
			process.removeProcessListener(listener);
		}
	}

	@Override
	public void request(TernDoc doc, ITernCompletionCollector collector)
			throws TernException {
		try {
			JsonObject jsonObject = makeRequest(doc);
			if (jsonObject != null) {
				Long startCh = getCh(jsonObject, "start");
				Long endCh = getCh(jsonObject, "end");
				int pos = 0;
				if (startCh != null && endCh != null) {
					pos = endCh.intValue() - startCh.intValue();
				}
				boolean isProperty = StringUtils.asBoolean(
						getText(jsonObject, IS_PROPERTY_PROPERTY), false);
				boolean isObjectKey = StringUtils.asBoolean(
						getText(jsonObject, IS_OBJECT_KEY_PROPERTY), false);
				JsonArray completions = (JsonArray) jsonObject
						.get("completions");
				if (completions != null) {
					for (JsonValue value : completions) {
						if (value.isString()) {
							collector.addProposal(value.asString(),
									value.asString(), null, null, null, null,
									startCh != null ? startCh.intValue() : 0,
									endCh != null ? endCh.intValue() : 0,
									isProperty, isObjectKey, value, this);
						} else {
							super.addProposal(value,
									startCh != null ? startCh.intValue() : 0,
									endCh != null ? endCh.intValue() : 0,
									isProperty, isObjectKey, collector);
						}
					}
				}
			}
		} catch (TernException e) {
			throw e;
		} catch (Throwable e) {
			throw new TernException(e);
		}
	}

	@Override
	public String getText(Object value) {
		return JsonHelper.getString((JsonValue) value);
	}

	@Override
	public Object getValue(Object value, String name) {
		return ((JsonObject) value).get(name);
	}

	private Long getCh(JsonObject data, String name) {
		JsonValue loc = data.get(name);
		if (loc == null) {
			return null;
		}
		if (loc.isNumber()) {
			return loc.asLong();
		}
		return loc != null ? JsonHelper.getLong((JsonObject) loc, "ch") : null;
	}

	@Override
	public void request(TernDoc doc, ITernDefinitionCollector collector)
			throws TernException {
		try {
			JsonObject jsonObject = makeRequest(doc);
			if (jsonObject != null) {
				Long startCh = getCh(jsonObject, "start");
				Long endCh = getCh(jsonObject, "end");
				String file = getText(jsonObject.get("file"));
				if (StringUtils.isEmpty(file)) {
					file = getText(jsonObject.get("origin"));
				}
				collector.setDefinition(file, startCh, endCh);
			}
		} catch (Throwable e) {
			throw new TernException(e);
		}
	}

	@Override
	public void request(TernDoc doc, ITernTypeCollector collector)
			throws TernException {
		try {
			JsonObject jsonObject = makeRequest(doc);
			if (jsonObject != null) {
				String type = getText(jsonObject.get("type"));
				boolean guess = JsonHelper.getBoolean(jsonObject, "guess",
						false);
				String name = getText(jsonObject.get("name"));
				String exprName = getText(jsonObject.get("exprName"));
				String documentation = getText(jsonObject.get("doc"));
				String url = getText(jsonObject.get("url"));
				String origin = getText(jsonObject.get("origin"));
				collector.setType(type, guess, name, exprName, documentation,
						url, origin, jsonObject, this);
			}
		} catch (Throwable e) {
			throw new TernException(e);
		}
	}

	@Override
	public void request(TernDoc doc, ITernLintCollector collector)
			throws TernException {
		try {
			JsonObject jsonObject = makeRequest(doc);
			if (jsonObject != null) {
				JsonArray messages = (JsonArray) jsonObject.get("messages");
				if (messages != null) {
					TernLintQuery query = (TernLintQuery) doc.getQuery();
					if (query.isGroupByFiles()) {
						JsonObject filesObject = null;
						String file = null;
						for (JsonValue files : messages) {
							filesObject = (JsonObject) files;
							file = getText(filesObject.get("file"));
							collector.startLint(file);

							JsonArray messagesFile = (JsonArray) filesObject
									.get("messages");
							if (messagesFile != null) {
								addMessages(messagesFile, collector);
							}
							collector.endLint(file);
						}
					} else {
						addMessages(messages, collector);
					}
				}
			}
		} catch (Throwable e) {
			throw new TernException(e);
		}

	}

	protected void addMessages(JsonArray messages, ITernLintCollector collector) {
		String message = null;
		String severity = null;
		String file = null;
		JsonObject messageObject = null;
		for (JsonValue value : messages) {
			messageObject = (JsonObject) value;
			message = getText(messageObject.get("message"));
			severity = getText(messageObject.get("severity"));
			Long startCh = getCh(messageObject, "from");
			Long endCh = getCh(messageObject, "to");
			file = getText(messageObject.get("file"));
			collector.addMessage(message, startCh, endCh, severity, file);
		}
	}

	@Override
	public void request(TernDoc doc, ITernGuessTypesCollector collector)
			throws TernException {
		try {
			JsonObject jsonObject = makeRequest(doc);
			if (jsonObject != null) {
				JsonArray args = (JsonArray) jsonObject.get("args");
				if (args != null) {
					JsonArray namesForArg;
					String argType = null;
					int argIndex = 0;
					for (JsonValue arg : args) {
						argType = JsonHelper.getString(arg);
						namesForArg = (JsonArray) jsonObject.get(argType);
						for (JsonValue name : namesForArg) {
							collector.addProposal(argIndex,
									JsonHelper.getString(name));
						}
						argIndex++;
					}
				}
			}
		} catch (Throwable e) {
			throw new TernException(e);
		}

	}

	@Override
	public void doDispose() {
		if (process != null) {
			process.kill();
		}
		this.baseURL = null;
		this.process = null;
	}

	/**
	 * Set the timeout to use when node.js starts to retrieve the node.js port
	 * in {@link NodejsProcess#start(long, int)} from the given project.
	 */
	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}

	/**
	 * Returns the timeout to use when node.js starts to retrieve the node.js
	 * port in {@link NodejsProcess#start(long, int)} from the given project.
	 * 
	 * @return
	 */
	public long getTimeout() {
		return timeout;
	}

	public void setTestNumber(int testNumber) {
		this.testNumber = testNumber;
	}

	public int getTestNumber() {
		return testNumber;
	}

	/**
	 * Set false if the server will shut itself down after five minutes of
	 * inactivity and true otherwise.
	 * 
	 * @param persistent
	 */
	public void setPersistent(boolean persistent) {
		this.persistent = persistent;
	}

	/**
	 * Returns false if the server will shut itself down after five minutes of
	 * inactivity and true otherwise.
	 * 
	 * @return
	 */
	public boolean isPersistent() {
		return persistent;
	}

}
