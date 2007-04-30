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
import org.osgi.framework.*;
import org.osgi.service.event.*;
import org.osgi.service.log.LogService;

public class EventHandlerWrapper implements EventHandler {
	private final ServiceReference reference;
	private final LogService log;
	private final BundleContext context;
	private EventHandler handler;
	private String[] topics;
	private Filter filter;
	private boolean ignore;
	
	public EventHandlerWrapper(ServiceReference reference, BundleContext context, LogService log) {
		this.reference = reference;
		this.context = context;
		this.log = log;
	}

	public synchronized void init() {
		topics = null;
		Object o = reference.getProperty(EventConstants.EVENT_TOPIC);
		if (o instanceof String) {
			topics = new String[] {(String)o};
		}
		if (o instanceof String[]) {
			topics = (String[]) o;
		}
		
		ignore = false;
		filter = null;
		o = reference.getProperty(EventConstants.EVENT_FILTER);
		if (o instanceof String) {
			try {
				filter = context.createFilter((String)o);
			}
			catch (InvalidSyntaxException e) {
				log.log(LogService.LOG_ERROR,"exception thrown in BundleContext.createfilter(\""+o+"\". Ignored.", e);
				ignore = true;
			}
		}
	}

	public synchronized void flush() {
		if (handler != null) {
			context.ungetService(reference);
			handler = null;
		}
	}
	
	public synchronized boolean shouldReceive(Event event, Permission perm) {
		// quick test
		if (ignore) {
			return false;
		}
		
		// topic match
		if (topics == null) {
			// means no topics, the handler does not receive any events
			return false;
		}
		
		String eventTopic = event.getTopic();
		boolean topicMatch = false;
		for (int i = 0; i < topics.length; i++) {
			if (matchTopic(topics[i], eventTopic)) {
				topicMatch = true;
				break;
			}
		}
		if (!topicMatch) {
			return false;
		}
		
		// filter match
		if ((filter != null) && !event.matches(filter)) {
			return false;
		}
		
		// permission check
		if (perm != null) {
			Bundle bundle = reference.getBundle();
			if (bundle == null) {
				return false;
			}
			return bundle.hasPermission(perm);
		}

		return true;
	}

	/**
	 * Checks if a topic filter string matches the target topic string.
	 * 
	 * @param handlerTopic A topic filter like "company/product/*"
	 * @param eventTopic Target topic to be checked against like
	 *        "company/product/topicA"
	 * @return true if topicProperty matches topic, false if otherwise.
	 */
	private boolean matchTopic(String handlerTopic, String eventTopic) {
		if (handlerTopic.equals("*")) {
			return true;
		}
		if (handlerTopic.equals(eventTopic)) {
			return true;
		}
		
		if (handlerTopic.endsWith("/*")) {
			return eventTopic.regionMatches(0, handlerTopic, 0, handlerTopic.length()-1);
		}
		
		return false;
	}
	

	public void handleEvent(Event event) {
		Bundle bundle = reference.getBundle();
		if ((bundle == null) || ((bundle.getState() & Bundle.ACTIVE) == 0)) {
			// the receiver service or bundle being not active, no need
			// to dispatch
			return;
		}
		
		getHandler().handleEvent(event);
	}

	private synchronized EventHandler getHandler() {
		if (handler == null) {
			handler = (EventHandler)context.getService(reference);
		}
		return handler;
	}
	
}
