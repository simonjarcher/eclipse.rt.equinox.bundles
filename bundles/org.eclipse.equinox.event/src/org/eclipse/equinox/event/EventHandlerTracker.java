/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.equinox.event;

import java.security.Permission;
import org.eclipse.osgi.framework.eventmgr.EventDispatcher;
import org.eclipse.osgi.framework.eventmgr.EventListeners;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.*;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;

public class EventHandlerTracker extends ServiceTracker implements EventDispatcher{
	private final LogService log;

	public EventHandlerTracker(BundleContext context, LogService log) {
		super(context, EventHandler.class.getName(), null);
		this.log = log;
	}

	public Object addingService(ServiceReference reference) {
		EventHandlerWrapper wrapper = new EventHandlerWrapper(reference, context, log);
		wrapper.init();
		return wrapper;
	}

	public void modifiedService(ServiceReference reference, Object service) {
		EventHandlerWrapper wrapper = (EventHandlerWrapper)service;
		wrapper.flush();
		wrapper.init();
	}

	public void removedService(ServiceReference reference, Object service) {
		EventHandlerWrapper wrapper = (EventHandlerWrapper)service;
		wrapper.flush();
	}

	public EventListeners getHandlers(Event event) {
		Object[] wrappers = getServices();
		if (wrappers == null) {
			return null;
		}
		
		SecurityManager sm = System.getSecurityManager();
		Permission perm = (sm == null) ? null : new TopicPermission(event.getTopic(), TopicPermission.SUBSCRIBE);

		EventListeners listeners = new EventListeners();
		boolean empty = true;
		for (int i = 0; i < wrappers.length; i++) {
			EventHandlerWrapper wrapper = (EventHandlerWrapper) wrappers[i];
			if (wrapper.shouldReceive(event, perm)) {
				listeners.addListener(wrapper, wrapper);
				empty = false;
			}
		}
		
		return empty ? null : listeners;
	}
	
	/**
	 * 
	 * Dispatches Event to EventHandlers
	 * 
	 * @param eventListener
	 * @param listenerObject
	 * @param eventAction
	 * @param eventObject
	 * @see org.eclipse.osgi.framework.eventmgr.EventDispatcher#dispatchEvent(java.lang.Object,
	 *      java.lang.Object, int, java.lang.Object)
	 */
	public void dispatchEvent(Object eventListener, Object listenerObject, int eventAction, Object eventObject) {
		try {
			((EventHandler) eventListener).handleEvent((Event) eventObject);
		}
		catch (Throwable t) {
			// log/handle any Throwable thrown by the listener
			log.log(LogService.LOG_ERROR, "Exception while dispatching event["+eventObject+"] to handler["+eventListener +"]", t);
		}
	}

}
