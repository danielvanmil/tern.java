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
package tern.server.rhino;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;

import tern.ITernFileSynchronizer;
import tern.ITernProject;
import tern.TernException;
import tern.server.AbstractTernServer;
import tern.server.DefaultResponseHandler;
import tern.server.IResponseHandler;
import tern.server.ITernDef;
import tern.server.ITernPlugin;
import tern.server.protocol.TernDoc;
import tern.server.protocol.completions.ITernCompletionCollector;
import tern.server.protocol.definition.ITernDefinitionCollector;
import tern.server.protocol.guesstypes.ITernGuessTypesCollector;
import tern.server.protocol.html.ScriptTagRegion;
import tern.server.protocol.lint.ITernLintCollector;
import tern.server.protocol.type.ITernTypeCollector;
import tern.server.rhino.loader.ClassPathScriptLoader;
import tern.server.rhino.loader.IScriptLoader;
import tern.utils.IOUtils;

/**
 * Tern server implemented with Mozilla Rhino.
 * 
 */
public class RhinoTernServer extends AbstractTernServer {

	private final Scriptable ternScope;

	private final IScriptLoader loader;

	private final String[] TERN_SCRIPTS = { "env.rhino.1.2.js", "json/json.js",
			"acorn/acorn.js", "acorn/acorn_loose.js", "acorn/util/walk.js",
			"tern/lib/signal.js", "tern/lib/tern.js", "tern/lib/def.js",
			"tern/lib/comment.js", "tern/lib/infer.js", "tern-server.js" };

	public RhinoTernServer() throws IOException {
		this((ITernProject) null);
	}

	public RhinoTernServer(ITernProject project) throws IOException {
		this(project, ClassPathScriptLoader.getInstance());
	}

	public RhinoTernServer(IScriptLoader loader) throws IOException {
		this(null, loader);
	}

	public RhinoTernServer(ITernProject project, IScriptLoader loader)
			throws IOException {
		super(project);
		this.loader = loader;
		Context cx = Context.enter();
		try {

			// Initialize the standard objects (Object, Function, etc.)
			// This must be done before scripts can be executed. Returns
			// a scope object that we use in later calls.
			this.ternScope = cx.initStandardObjects();

			// acorn uses self variables, define it here.
			cx.evaluateString(ternScope, "var self = this;", "", 1, null);

			cx.setOptimizationLevel(-1);
			loadTernScripts(cx, loader);

			ternScope.put("_server", ternScope, this);
		} finally {
			// Exit from the context.
			Context.exit();
		}
	}

	protected void loadTernScripts(Context cx, IScriptLoader loader)
			throws IOException {
		for (int i = 0; i < TERN_SCRIPTS.length; i++) {
			loadScript(cx, loader, TERN_SCRIPTS[i]);
		}
	}

	protected void loadScript(Context cx, IScriptLoader loader, String src)
			throws IOException {
		loader.loadScript(cx, ternScope, src);
	}

	public void addDef(ITernDef def) throws TernException {
		addDef(def, loader);
	}

	public void addDef(ITernDef def, IScriptLoader loader) throws TernException {
		Context cx = Context.enter();
		try {
			loader.loadScript(cx, ternScope, def.getPath(),
					"(function() {var def = ", "addDef(def);})();");
		} catch (IOException e) {
			throw new TernException(e);
		} finally {
			// Exit from the context.
			Context.exit();
		}
	}

	@Override
	public void addPlugin(ITernPlugin plugin) throws TernException {
		Context cx = Context.enter();
		try {
			Object functionArgs[] = { plugin.getPath() };
			Object fObj = ternScope.get("addPlugin", ternScope);
			Function f = (Function) fObj;
			f.call(cx, ternScope, ternScope, functionArgs);
		} finally {
			// Exit from the context.
			Context.exit();
		}
	}

	@Override
	public void addFile(String name, String text, ScriptTagRegion[] tags) {
		Context cx = Context.enter();
		try {
			// tern.js checks if file.text is typeof string
			// set java primitive wrap to false, otherwise tern.js throws error
			// ".files[n].text must be a string"
			cx.getWrapFactory().setJavaPrimitiveWrap(false);

			Object functionArgs[] = { name, text };
			Object fObj = ternScope.get("addFile", ternScope);
			Function f = (Function) fObj;
			f.call(cx, ternScope, ternScope, functionArgs);
		} finally {
			// Exit from the context.
			Context.exit();
		}
	}

	public void loadJS(File baseDir) {
		Context cx = Context.enter();
		try {
			Object fObj = ternScope.get("addFile", ternScope);
			Function f = (Function) fObj;
			loadJS(baseDir, f, cx);
		} finally {
			// Exit from the context.
			Context.exit();
		}
	}

	private void loadJS(File baseDir, Function f, Context cx) {
		if (baseDir.isFile()) {
			try {
				if (baseDir.getName().endsWith(".js")) {
					long start = System.currentTimeMillis();
					Object functionArgs[] = { baseDir.getPath(),
							IOUtils.toString(new FileInputStream(baseDir)) };
					f.call(cx, ternScope, ternScope, functionArgs);
					System.err.println(baseDir.getPath() + " =>"
							+ (System.currentTimeMillis() - start) + "ms");
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {
			if (!baseDir.getName().startsWith(".")
					&& !baseDir.getName().startsWith("test")) {
				for (File file : baseDir.listFiles()) {
					loadJS(file, f, cx);
				}
			}
		}

	}

	@Override
	public void request(TernDoc doc, IResponseHandler handler) {

		Context cx = Context.enter();
		try {
			// tern.js checks if file.text is typeof string
			// set java primitive wrap to false, otherwise tern.js throws error
			// ".files[n].text must be a string"
			cx.getWrapFactory().setJavaPrimitiveWrap(false);

			// Object jsObject = NativeJSON.parse(cx, ternScope,
			// doc.toJSONString(), null);
			Object jsObject = Context.javaToJS(doc.toString(), ternScope);
			Object functionArgs[] = { jsObject, handler,
					handler.isDataAsJsonString() };
			Object fObj = ternScope.get("request2", ternScope);
			Function f = (Function) fObj;
			f.call(cx, ternScope, ternScope, functionArgs);

			// Update file manager if needed.
			ITernFileSynchronizer fileSynchronizer = super
					.getFileSynchronizer();
			if (fileSynchronizer != null) {
				fileSynchronizer.filesUploaded(doc);
			}

		} finally {
			// Exit from the context.
			Context.exit();
		}

	}

	@Override
	public void request(TernDoc doc, ITernCompletionCollector collector)
			throws TernException {
		DefaultResponseHandler handler = new DefaultResponseHandler(true);
		request(doc, handler);
		Object data = handler.getData();
		NativeObject rhinoObject = (NativeObject) data;
		if (rhinoObject != null) {
			Double startCh = getCh(rhinoObject, "start");
			Double endCh = getCh(rhinoObject, "end");
			int pos = endCh.intValue() - startCh.intValue();
			// FIXME : retrieve values from completions
			boolean isProperty = false;
			boolean isObjectKey = false;
			List completions = (List) rhinoObject.get("completions",
					rhinoObject);
			for (Object object : completions) {

				addProposal(object, startCh != null ? startCh.intValue() : 0,
						endCh != null ? endCh.intValue() : 0, isProperty,
						isObjectKey, collector);
			}
		}
	}

	@Override
	public String getText(Object value) {
		if (value == null) {
			return null;
		}
		return value.toString();
	}

	@Override
	public Object getValue(Object value, String name) {
		NativeObject rhinoObject = (NativeObject) value;
		return rhinoObject.get(name, rhinoObject);
	}

	private Double getCh(NativeObject data, String pos) {
		NativeObject loc = (NativeObject) data.get(pos, data);
		return (Double) loc.get("ch", loc);
	}

	@Override
	public void request(TernDoc doc, ITernDefinitionCollector collector)
			throws TernException {

	}

	@Override
	public void request(TernDoc doc, ITernTypeCollector collector)
			throws TernException {

	}

	@Override
	public void request(TernDoc doc, ITernLintCollector collector)
			throws TernException {

	}

	@Override
	public void request(TernDoc doc, ITernGuessTypesCollector collector)
			throws TernException {

	}

	@Override
	public void doDispose() {
		fireEndServer();
	}
}
