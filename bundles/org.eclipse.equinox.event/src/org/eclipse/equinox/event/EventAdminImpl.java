/*******************************************************************************
 * Copyright (c) 2005, 2007 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.equinox.event;

import org.eclipse.osgi.framework.eventmgr.*;
import org.osgi.framework.*;
import org.osgi.service.event.*;
import org.osgi.service.log.LogService;

/**
 * Implementation of org.osgi.service.event.EventAdmin. EventAdminImpl uses
 * org.eclipse.osgi.framework.eventmgr.EventManager. It is assumeed
 * org.eclipse.osgi.framework.eventmgr package is exported by some other bundle.
 */
public class EventAdminImpl implements EventAdmin {
	private final LogTracker log;
	private final EventHandlerTracker handlers;
	private volatile EventManager		eventManager;

	/**
	 * Constructer for EventAdminImpl.
	 * 
	 * @param context BundleContext
	 */
	EventAdminImpl(BundleContext context) {
		super();
		log = new LogTracker(context, System.out);
		handlers = new EventHandlerTracker(context, log);
	}
	
	/**
	 * This method should be called before registering EventAdmin service
	 */
	void start() {
		log.open();
		eventManager = new EventManager("EventAdmin Async Event Dispatcher Thread");
		handlers.open();
	}

	/**
	 * This method should be called after unregistering EventAdmin service
	 */
	void stop() {
		handlers.close();
		eventManager.close();
		eventManager = null;	// signify we have stopped
		log.close();
	}

	/**
	 * @param event
	 * @see org.osgi.service.event.EventAdmin#postEvent(org.osgi.service.event.Event)
	 */
	public void postEvent(Event event) {
		dispatchEvent(event, true);
	}

	/**
	 * @param event
	 * @see org.osgi.service.event.EventAdmin#sendEvent(org.osgi.service.event.Event)
	 */
	public void sendEvent(Event event) {
		dispatchEvent(event, false);
	}

	/**
	 * Internal main method for sendEvent() and postEvent(). Dispatching an
	 * event to EventHandler. All exceptions are logged except when dealing with
	 * LogEntry.
	 * 
	 * @param event to be delivered
	 * @param isAsync must be set to true for syncronous event delivery, false
	 *        for asyncronous delivery.
	 */
	private void dispatchEvent(Event event, boolean isAsync) {
		try {
			if (eventManager == null) {
				// EventAdmin is stopped
				return;
			}
			if (event == null) {
				log.log(LogService.LOG_ERROR, "Null event is passed to EventAdmin. Ignored.");
				return;
			}
			if (!checkTopicPermissionPublish(event.getTopic())) {
				log.log(LogService.LOG_ERROR, "Caller bundle doesn't have TopicPermission for topic="+event.getTopic());
				return;
			}
			
			EventListeners listeners = handlers.getHandlers(event);
			if (listeners == null) {
				//No permitted EventHandler exists. Do nothing.
				return;
			}
			
			// Create the listener queue for this event delivery
			ListenerQueue listenerQueue = new ListenerQueue(eventManager);
			// Add the listeners to the queue and associate them with the event
			// dispatcher
			listenerQueue.queueListeners(listeners, handlers);
			// Deliver the event to the listeners.
			if (isAsync) {
				listenerQueue.dispatchEventAsynchronous(0, event);
			}
			else {
				listenerQueue.dispatchEventSynchronous(0, event);
			}
		}
		catch (Throwable t) {
			log.log(LogService.LOG_ERROR,"Exception thrown while dispatching an event, event = "+event, t);
		}
	}

	/**
	 * Checks if the caller bundle has right PUBLISH TopicPermision.
	 * 
	 * @param topic
	 * @return true if it has the right permission, false otherwise.
	 */
	private boolean checkTopicPermissionPublish(String topic) {
		SecurityManager sm = System.getSecurityManager();
		if (sm == null) {
			return true;
		}
		try {
			sm.checkPermission(new TopicPermission(topic, TopicPermission.PUBLISH));
		}
		catch (SecurityException e) { // fall through and return false
			return false;
		}
		return true;
	}

}
