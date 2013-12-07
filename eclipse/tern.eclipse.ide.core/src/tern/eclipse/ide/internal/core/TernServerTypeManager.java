/*******************************************************************************
 * Copyright (c) 2013 Angelo ZERR.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:      
 *     Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 *******************************************************************************/
package tern.eclipse.ide.internal.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionDelta;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IRegistryChangeEvent;
import org.eclipse.core.runtime.IRegistryChangeListener;
import org.eclipse.core.runtime.Platform;

import tern.eclipse.ide.core.ITernServerType;
import tern.eclipse.ide.core.ITernServerTypeManager;
import tern.eclipse.ide.core.TernCorePlugin;
import tern.server.ITernPlugin;
import tern.server.TernPlugin;

public class TernServerTypeManager implements ITernServerTypeManager,
		IRegistryChangeListener {

	private static final String EXTENSION_TERN_SERVER_TYPES = "ternServerTypes";

	private static final TernServerTypeManager INSTANCE = new TernServerTypeManager();

	// cached copy of all tern server type
	private List<ITernServerType> ternServerTypes;

	private boolean registryListenerIntialized;

	public static TernServerTypeManager getManager() {
		return INSTANCE;
	}

	public TernServerTypeManager() {
		this.registryListenerIntialized = false;
	}

	@Override
	public void registryChanged(final IRegistryChangeEvent event) {
		IExtensionDelta[] deltas = event.getExtensionDeltas(
				TernCorePlugin.PLUGIN_ID, EXTENSION_TERN_SERVER_TYPES);
		if (deltas != null) {
			for (IExtensionDelta delta : deltas)
				handleTernServerTypeDelta(delta);
		}
	}

	@Override
	public ITernServerType[] getTernServerTypes() {
		if (ternServerTypes == null)
			loadTernServerTypes();

		ITernServerType[] st = new ITernServerType[ternServerTypes.size()];
		ternServerTypes.toArray(st);
		return st;
	}

	@Override
	public ITernServerType findTernServerType(String id) {
		if (id == null)
			throw new IllegalArgumentException();

		if (ternServerTypes == null)
			loadTernServerTypes();

		Iterator<ITernServerType> iterator = ternServerTypes.iterator();
		while (iterator.hasNext()) {
			ITernServerType serverType = (ITernServerType) iterator.next();
			if (id.equals(serverType.getId()))
				return serverType;
		}
		return null;
	}

	/**
	 * Load the tern server types.
	 */
	private synchronized void loadTernServerTypes() {
		if (ternServerTypes != null)
			return;

		Trace.trace(Trace.EXTENSION_POINT,
				"->- Loading .ternServerTypes extension point ->-");

		IExtensionRegistry registry = Platform.getExtensionRegistry();
		IConfigurationElement[] cf = registry.getConfigurationElementsFor(
				TernCorePlugin.PLUGIN_ID, EXTENSION_TERN_SERVER_TYPES);
		List<ITernServerType> list = new ArrayList<ITernServerType>(cf.length);
		addTernServerTypes(cf, list);
		addRegistryListenerIfNeeded();
		ternServerTypes = list;

		Trace.trace(Trace.EXTENSION_POINT,
				"-<- Done loading .ternServerTypes extension point -<-");
	}

	/**
	 * Load the tern server types.
	 */
	private synchronized void addTernServerTypes(IConfigurationElement[] cf,
			List<ITernServerType> list) {
		for (IConfigurationElement ce : cf) {
			try {
				list.add(new TernServerType(ce));
				Trace.trace(Trace.EXTENSION_POINT,
						"  Loaded serverType: " + ce.getAttribute("id"));
			} catch (Throwable t) {
				Trace.trace(
						Trace.SEVERE,
						"  Could not load serverType: " + ce.getAttribute("id"),
						t);
			}
		}
	}

	protected void handleTernServerTypeDelta(IExtensionDelta delta) {
		if (ternServerTypes == null) // not loaded yet
			return;

		IConfigurationElement[] cf = delta.getExtension()
				.getConfigurationElements();

		List<ITernServerType> list = new ArrayList<ITernServerType>(
				ternServerTypes);
		if (delta.getKind() == IExtensionDelta.ADDED) {
			addTernServerTypes(cf, list);
		} else {
			int size = list.size();
			TernServerType[] st = new TernServerType[size];
			list.toArray(st);
			int size2 = cf.length;

			for (int i = 0; i < size; i++) {
				for (int j = 0; j < size2; j++) {
					if (st[i].getId().equals(cf[j].getAttribute("id"))) {
						st[i].dispose();
						list.remove(st[i]);
					}
				}
			}
		}
		ternServerTypes = list;
	}

	private void addRegistryListenerIfNeeded() {
		if (registryListenerIntialized)
			return;

		IExtensionRegistry registry = Platform.getExtensionRegistry();
		registry.addRegistryChangeListener(this, TernCorePlugin.PLUGIN_ID);
		registryListenerIntialized = true;
	}

	public void initialize() {

	}

	public void destroy() {
		List<ITernServerType> types = new ArrayList<ITernServerType>(
				ternServerTypes);
		for (ITernServerType type : types) {
			type.dispose();
			ternServerTypes.remove(type);
		}
		Platform.getExtensionRegistry().removeRegistryChangeListener(this);
	}

	@Override
	public void refresh() {
		for (ITernServerType type : ternServerTypes) {
			type.dispose();
		}
	}

	@Override
	public ITernPlugin[] getTernPlugins() {
		// TODO : manage tern plugins with extension point
		return TernPlugin.values();
	}

	@Override
	public ITernPlugin findTernPlugin(String name) {
		return TernPlugin.getTernPlugin(name);
	}

}
