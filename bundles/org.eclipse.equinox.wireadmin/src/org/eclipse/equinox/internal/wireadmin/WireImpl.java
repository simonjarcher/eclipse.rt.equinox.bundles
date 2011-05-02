/*******************************************************************************
 * Copyright (c) 1997, 2008 by ProSyst Software GmbH
 * http://www.prosyst.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    ProSyst Software GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.wireadmin;

import java.util.*;
import org.osgi.framework.*;
import org.osgi.service.wireadmin.*;

/**
 * @author Pavlin Dobrev
 * @version 1.0
 */

class WireImpl implements Wire, ServiceListener {

	private BundleContext bc;

	/** Holds all the properties associated with this <code>Wire</code> object */
	private WireProperties properties;

	/** Holds a service reference to the associated <code>Producer</code> */
	ServiceReference producerRef;

	/** Holds a service reference to the associated <code>Consumer</code> */
	ServiceReference consumerRef;

	private Producer producer;
	private Consumer consumer;

	private Class[] flavors;

	/** Holds the last value passed through this <code>Wire</code>. */
	private Object lastValue;

	private Vector envelopes;

	/**
	 * <code>WireAdmin</code> object whit which this <code>Wire</code> was
	 * created.
	 */
	private WireAdminImpl parent;

	private Filter filter = null;

	/** Holds the time of last <code>Consumer</code> update in milliseconds */
	private long lastUpdateTime = -1;

	/** Holds the available wire values (filter attributes) */
	private Hashtable wireValues;

	/* holds a list of scopes */
	private String[] scope;

	/** Indicates that this <code>Wire</code> object has been deleted */
	boolean isValid = true;

	private boolean interoperate = true;

	private boolean allAccepted = true;

	/**
	 * Creates a <code>Wire</code> object, representing a connection between a
	 * <code>Producer</code> and <code>Consumer</code>.
	 * 
	 * @param bc
	 *            is the Wiring <code>BundleContext</code>.
	 * @param parent
	 *            is the <code>WireAdmin</code> which is the creator of this
	 *            <code>Wire</code>.
	 * @param wirePID
	 *            is a <code>String</code> holding a unique presistent
	 *            identifier of this <code>Wire</code>, generated by the
	 *            parent <code>WireAdmin</code>.
	 * @param properties
	 *            is a collection of the initial wire properties.
	 */
	WireImpl(BundleContext bc, WireAdminImpl parent, Dictionary properties) {
		this.bc = bc;
		this.parent = parent;
		this.properties = new WireProperties();

		for (Enumeration en = properties.keys(); en.hasMoreElements();) {
			Object key = en.nextElement();
			this.properties.put0(key, properties.get(key));
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#isValid()
	 */
	public boolean isValid() {
		return isValid;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#getFlavors()
	 */
	public Class[] getFlavors() {
		return isConnected() ? flavors : null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#getProperties()
	 */
	public Dictionary getProperties() {
		return properties;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#getLastValue()
	 */
	public synchronized Object getLastValue() {
		return lastValue;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#update(java.lang.Object)
	 */
	public synchronized void update(Object value) {
		if (!isConnected() || !interoperate || !isAcceptable(value)) {
			return;
		}

		if (value instanceof Envelope) {
			Envelope e = (Envelope) value;
			if (!hasScope(e.getScope())) {
				if (Activator.LOG_DEBUG) {
					Activator.log.debug(Activator.PREFIX + "Try to pass a value in an Envelop without permission, silent return.", null);
				}
				return;
			}
		}

		if (filter != null) {

			wireValues.put(WireConstants.WIREVALUE_CURRENT, value);

			// #3329
			if (lastValue != null) {
				wireValues.put(WireConstants.WIREVALUE_PREVIOUS, lastValue);
				wireValues.put(WireConstants.WIREVALUE_ELAPSED, new Long(System.currentTimeMillis() - lastUpdateTime));
			}

			if (Number.class.isInstance(value) && Number.class.isInstance(lastValue)) {
				double val = ((Number) value).doubleValue();
				double lastVal = ((Number) lastValue).doubleValue();

				wireValues.put(WireConstants.WIREVALUE_DELTA_ABSOLUTE, new Double(Math.abs(val - lastVal)));
				// #3328
				wireValues.put(WireConstants.WIREVALUE_DELTA_RELATIVE, new Double(Math.abs(1 - lastVal / val)));
			} else {
				wireValues.remove(WireConstants.WIREVALUE_DELTA_ABSOLUTE);
				wireValues.remove(WireConstants.WIREVALUE_DELTA_RELATIVE);
			}

			if (!filter.match(wireValues)) {
				if (Activator.LOG_DEBUG) {
					Activator.log.debug(0, 10012, filter + " / " + value, null, false);
				}
				return;
			}
		}

		if (consumer != null) {
			try {
				consumer.updated(this, value);
			} catch (Throwable t) {
				parent.notifyListeners(this, WireAdminEvent.CONSUMER_EXCEPTION, t);
				return;
			}
			lastValue = value;
			lastUpdateTime = System.currentTimeMillis();
			parent.notifyListeners(this, WireAdminEvent.WIRE_TRACE, null);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#poll()
	 */
	public synchronized Object poll() {
		Object value = null;
		if (isConnected() && interoperate) {
			try {
				value = producer.polled(this);
			} catch (Throwable t) {
				// no exception in the Producer must prevent correct Wire
				// functioning
				parent.notifyListeners(this, WireAdminEvent.PRODUCER_EXCEPTION, t);
				return null;
			}
			parent.notifyListeners(this, WireAdminEvent.WIRE_TRACE, null);
			if (!isAcceptable(value) && (!(value instanceof Envelope[]))) {
				value = null;
			}
		}

		if (value != null) {
			lastValue = value;
			if (value instanceof Envelope[]) {

				if (allAccepted) {
					return value;
				}
				Envelope[] envs = (Envelope[]) value;
				if (scope == null) {
					return value;
				}

				if (envelopes == null) {
					envelopes = new Vector(envs.length);
				}
				boolean changed = false;
				for (int i = 0; i < envs.length; i++) {
					if (hasScope(envs[i].getScope())) {
						envelopes.addElement(envs[i]);
					} else {
						changed = true;
					}
				}

				if (changed) {
					value = new Envelope[envelopes.size()];
					envelopes.copyInto((Envelope[]) value);
					envelopes.removeAllElements();
				}
			}
		}
		return value;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#isConnected()
	 */
	public boolean isConnected() {
		return isValid && (consumerRef != null) && (producerRef != null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#getScope()
	 */
	public synchronized String[] getScope() {
		return scope;
	}

	private void setScope() {
		if ((producerRef == null) || (consumerRef == null)) {
			return;
		}

		Vector prodScope = checkPermission((String[]) producerRef.getProperty(WireConstants.WIREADMIN_PRODUCER_SCOPE), WirePermission.PRODUCE, producerRef.getBundle());

		Vector consScope = checkPermission((String[]) consumerRef.getProperty(WireConstants.WIREADMIN_CONSUMER_SCOPE), WirePermission.CONSUME, consumerRef.getBundle());

		if ((prodScope == null) || (consScope == null)) {
			return;
		}

		if ((consScope.size() == 1) && consScope.elementAt(0).equals("*")) {
			scope = new String[prodScope.size()];
			prodScope.copyInto(scope);
			return;
		}

		if ((prodScope.size() != ((String[]) producerRef.getProperty(WireConstants.WIREADMIN_PRODUCER_SCOPE)).length) || (consScope.size() != ((String[]) consumerRef.getProperty(WireConstants.WIREADMIN_CONSUMER_SCOPE)).length)) {
			this.allAccepted = false;
		}

		Vector cloning = (Vector) prodScope.clone();

		for (Enumeration en = cloning.elements(); en.hasMoreElements();) {
			Object next = en.nextElement();
			if (!consScope.contains(next)) {
				prodScope.removeElement(next);
				this.allAccepted = false;
			}
		}

		scope = new String[prodScope.size()];
		prodScope.copyInto(scope);
	}

	private static Vector checkPermission(String[] scope, String action, Bundle b) {
		if (scope == null) {
			return null;
		}

		Vector v = new Vector();
		for (int i = 0; i < scope.length; i++) {
			WirePermission wp = new WirePermission(scope[i], action);
			if (b.hasPermission(wp)) {
				v.addElement(scope[i]);
			}
		}
		return v;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.service.wireadmin.Wire#hasScope(java.lang.String)
	 */
	public boolean hasScope(String name) {
		if ((scope == null) || ((scope.length == 1) && scope[0].equals("*"))) {
			return true;
		}

		for (int i = 0; i < scope.length; i++) {
			if (name.equals(scope[i]) || scope[i].equals(WireConstants.WIREADMIN_SCOPE_ALL[0])) {
				return true;
			}
		}
		return false;
	}

	// Utility methods
	/**
	 * Creates a string representation for this <code>Wire</code>
	 * 
	 * @return a string representation of this object.
	 */
	public String toString() {
		StringBuffer sb = new StringBuffer(100);
		sb.append("Wire[PID=");
		sb.append(properties.get(WireConstants.WIREADMIN_PID));
		sb.append(";prodPID=");
		sb.append(properties.get(WireConstants.WIREADMIN_PRODUCER_PID));
		sb.append(";consPID=");
		sb.append(properties.get(WireConstants.WIREADMIN_CONSUMER_PID));
		sb.append(";connected=");
		sb.append(isConnected());
		// sb.append(";valid=");
		// sb.append(isValid());
		// sb.append(";Scope={");
		// if (scope == null) {
		// sb.append("null");
		// } else {
		// for (int i = 0; i < scope.length; i++) {
		// sb.append(scope[i]);
		// if (i != scope.length - 2) {
		// sb.append(", ");
		// }
		// }
		// }
		sb.append("}]");

		return sb.toString();
	}

	/**
	 * This method starts tracking of
	 * <code>Producer</code> <code>Consumer</code>services associated with
	 * this <code>Wire</code> object. First we check if there are already such
	 * services started on the framework, after that two
	 * <code>ServiceTracker</code> objects are created to handle
	 * <code>Producer</code> and <code>Consumer</code> service tracking.
	 */
	synchronized void start() {
		String producerPID = (String) properties.get(WireConstants.WIREADMIN_PRODUCER_PID);
		String consumerPID = (String) properties.get(WireConstants.WIREADMIN_CONSUMER_PID);
		updateListenerFilter();
		// check if there are such services already started
		ServiceReference ref = getSingleRef(Producer.class.getName(), producerPID);

		if (ref != null) {
			serviceRegistered(ref);
		}

		ref = getSingleRef(Consumer.class.getName(), consumerPID);

		if (ref != null) {
			serviceRegistered(ref);
		}

		if (Activator.LOG_DEBUG) {
			Activator.log.debug(0, 10013, properties.get(WireConstants.WIREADMIN_PID).toString(), null, false);
		}
	}

	void updateListenerFilter() {
		String producerPID = escapeSpecialCharacters((String) properties.get(WireConstants.WIREADMIN_PRODUCER_PID));
		String consumerPID = escapeSpecialCharacters((String) properties.get(WireConstants.WIREADMIN_CONSUMER_PID));

		try {
			// Create filter for tracking services regstered as Consumer's with
			// the current
			// WIRE_CONSUMER_PID and as Producer's with current
			// WIRE_PRODUCER_PID
			// escaping *, ) and ( in the pid.
			StringBuffer sb = new StringBuffer();// "(");
			sb.append('(');
			sb.append('|');

			sb.append('(').append('&');
			sb.append('(').append(Constants.SERVICE_PID).append('=').append(consumerPID).append(')');
			sb.append('(').append(Constants.OBJECTCLASS).append('=').append(Consumer.class.getName()).append(')');
			sb.append(')');

			sb.append('(').append('&');
			sb.append('(').append(Constants.SERVICE_PID).append('=').append(producerPID).append(')');
			sb.append('(').append(Constants.OBJECTCLASS).append('=').append(Producer.class.getName()).append(')');
			sb.append(')');

			if (consumerRef != null) {// in case service.pid changes
				Long id = (Long) consumerRef.getProperty(Constants.SERVICE_ID);
				sb.append('(').append(Constants.SERVICE_ID).append('=').append(id).append(')');
			}

			if (producerRef != null) {// in case service.pid changes
				Long id = (Long) producerRef.getProperty(Constants.SERVICE_ID);
				sb.append('(').append(Constants.SERVICE_ID).append('=').append(id).append(')');
			}

			sb.append(')');

			// System.out.println("Filter is: " + sb.toString());

			bc.addServiceListener(this, sb.toString());
		} catch (InvalidSyntaxException ise) {
			/* Syntax is valid */
		}

	}

	private ServiceReference getSingleRef(String clazz, String pid) {
		ServiceReference[] ref = null;

		try {
			ref = bc.getServiceReferences(clazz, "(" + Constants.SERVICE_PID + "=" + escapeSpecialCharacters(pid) + ")");
		} catch (InvalidSyntaxException e) {
		}

		if (ref != null) {
			if (ref.length > 1) {
				if (Activator.LOG_DEBUG) {
					Activator.log.debug(Activator.PREFIX + "Found more than one " + clazz + " services registered with the same pid: " + pid + "Wire was not created, please unregister all services which duplicate the pid.", null);
				}

				parent.deleteWire(this);
			} else if (ref.length == 1) {
				return ref[0];
			}
		}
		return null;
	}

	/**
	 * Stops service tracking, removes this <code>Wire</code> from the wire
	 * lists of the associated Consumer and Producer services and informs them
	 * for disconnecting.
	 */
	synchronized void stop() {
		if (!isValid)
			return; // already stopped

		if (Activator.LOG_DEBUG) {
			Activator.log.debug(0, 10014, this.toString(), null, false);
		}
		bc.removeServiceListener(this);
		if ((producerRef != null) && (consumerRef != null)) {
			// if this wire was connected
			isValid = false;
			informServices();
		}
		isValid = false;

		if (producerRef != null) {
			bc.ungetService(producerRef);
		}
		if (consumerRef != null) {
			bc.ungetService(consumerRef);
		}

		// let gc do his work
		producerRef = null;
		consumerRef = null;

		producer = null;
		consumer = null;

		lastValue = null;
		parent = null;
		filter = null;
		wireValues = null;
		scope = null;
		bc = null;
	}

	/**
	 * This method is invoked when the Wiring tracker detects a service
	 * registration or when the Wire is created The service type is determined
	 * (Producer or Consumer) and added to this <code>Wire</code>. Both
	 * Consumer and Producer (if available) are informed for connecting.
	 * 
	 * @param sRef
	 *            the service reference of the wire's producer or consumer
	 * @param notifyService
	 *            specifies whether notification of the Producer/Consumer is
	 *            necessary if it is the only one available part.
	 */
	private void serviceRegistered(ServiceReference sRef) {
		String pid = (String) sRef.getProperty(Constants.SERVICE_PID);

		if (pid.equals(properties.get(WireConstants.WIREADMIN_PRODUCER_PID))) {
			if (producerRef == null) {
				this.producerRef = sRef;
				this.producer = (Producer) bc.getService(producerRef);
				if (Activator.LOG_DEBUG) {
					Activator.log.debug(Activator.PREFIX + "Wire " + properties.get(WireConstants.WIREADMIN_PID) + " detected producer " + pid, null);
				}
			} else
				return;
		} else {
			if (consumerRef == null) {
				if (Activator.LOG_DEBUG) {
					Activator.log.debug(Activator.PREFIX + "Wire " + properties.get(WireConstants.WIREADMIN_PID) + " detected consumer " + pid, null);
				}
				this.consumerRef = sRef;
				this.consumer = (Consumer) bc.getService(consumerRef);
				try {
					this.flavors = (Class[]) consumerRef.getProperty(WireConstants.WIREADMIN_CONSUMER_FLAVORS);
				} catch (ClassCastException cce) {
					/* won't be initialized */
				}
			} else
				return;
		}

		if (isConnected()) {
			// inform the Producer/Consumer for connecting
			setScope();
			informServices();
			parent.notifyListeners(this, WireAdminEvent.WIRE_CONNECTED, null);
			checkInteroperability();
		} else if (!parent.hasAConnectedWire(pid.equals(properties.get(WireConstants.WIREADMIN_PRODUCER_PID)), pid)) {
			// this service has no connected wire objects attached to it
			if (Activator.LOG_DEBUG) {
				Activator.log.debug(0, 10015, pid, null, false);
			}
			// if (notifyService) { // The service is notified if it is only
			// just registered
			// informServices();
			// }
		}

		updateListenerFilter();
		checkWireFilter();
	}

	private void checkWireFilter() {
		boolean performFiltering = ((producerRef != null) && producerRef.getProperty(WireConstants.WIREADMIN_PRODUCER_FILTERS) == null) && (properties.get(WireConstants.WIREADMIN_FILTER) != null);

		if (performFiltering) {
			if ((wireValues == null)) {
				wireValues = new Hashtable(6, 1.0f);
			}

			try {
				filter = bc.createFilter((String) properties.get(WireConstants.WIREADMIN_FILTER));
			} catch (InvalidSyntaxException ise) {
				if (Activator.LOG_DEBUG) {
					Activator.log.debug(Activator.PREFIX + "Filter syntax is invalid, filtering won't be made", null);
				}
			}
		} else {
			filter = null;
		}
	}

	private void serviceModified(ServiceReference sRef) { // fix #1073
		String pid = (String) sRef.getProperty(Constants.SERVICE_PID);

		if (sRef.equals(producerRef)) {
			String currentPID = (String) properties.get(WireConstants.WIREADMIN_PRODUCER_PID);

			if (!currentPID.equals(pid)) {
				// System.out.println("The pid of the PRODUCER is changed");
				serviceUnregistered(sRef);
				return;
			}
		}

		if (sRef.equals(consumerRef)) {
			String currentPID = (String) properties.get(WireConstants.WIREADMIN_CONSUMER_PID);

			if (!currentPID.equals(pid)) {
				serviceUnregistered(sRef);
				// System.out.println("The pid of the CONSUMER is changed");
				return;
			}
			try {
				this.flavors = (Class[]) consumerRef.getProperty(WireConstants.WIREADMIN_CONSUMER_FLAVORS);
			} catch (ClassCastException cce) {
				/* won't be initialized */
			}
		}

		if (producerRef == null || consumerRef == null) {
			// System.out.println("Check if PID is BACK");
			serviceRegistered(sRef);
			return;
		}

		if (!isConnected()) {
			return;
		}

		setScope();
		checkInteroperability();
		checkWireFilter();
	}

	private void checkInteroperability() {
		String[] p = (String[]) producerRef.getProperty(WireConstants.WIREADMIN_PRODUCER_COMPOSITE);
		String[] c = (String[]) consumerRef.getProperty(WireConstants.WIREADMIN_CONSUMER_COMPOSITE);

		if ((p != null) && (c != null)) {
			for (int i = 0; i < p.length; i++) {
				for (int j = 0; j < c.length; j++) {
					if (p[i].equals(c[j])) {
						// found at least one match
						interoperate = true;
						return;
					}
				}
			}
			if (Activator.LOG_DEBUG) {
				Activator.log.debug(0, 10016, this.toString(), null, false);
			}
			interoperate = false;
		} else {
			interoperate = true;
		}
	}

	private void serviceUnregistered(ServiceReference sRef) {
		boolean lastStatus = isConnected();

		if (sRef.equals(producerRef)) {
			this.producerRef = null;
			this.producer = null;
		} else if (sRef.equals(consumerRef)) {
			this.consumerRef = null;
			this.consumer = null;
			this.flavors = null;
		} else {
			if (Activator.LOG_DEBUG) {
				Activator.log.debug(Activator.PREFIX + "Unregistering another consumer with the same pid, ignoring it ...", null);
			}
			return;
		}

		updateListenerFilter();

		if (lastStatus) {
			// last wire state was connected - now it is disconnected
			informServices();
			parent.notifyListeners(this, WireAdminEvent.WIRE_DISCONNECTED, null);
		}
		if (bc != null) {
			bc.ungetService(sRef);
		}

	}

	/**
	 * This method checks if the given String contains one of the characters *, (,
	 * and ) which have a special meaning for the LDAP search filters. If there
	 * are such characters they are escaped with the backslash character ('\').
	 */
	static String escapeSpecialCharacters(String s) {
		char[] content = s.toCharArray();
		StringBuffer result = new StringBuffer(s);
		int offset = 0;

		for (int i = 0; i < content.length; i++) {
			if ((content[i] == 40) || (content[i] == 41) || (content[i] == 42)) {
				result.insert(i + offset, "\\"); // fix #1078
				offset++;
			}
		}
		return result.toString();
	}

	/**
	 * Change the properties of this wire. If the changes concern one of the
	 * properties WIRE_PRODUCER_PID and WIRE_CONSUMER_PID this wire must be
	 * stopped and started again to begin tracking of the new Services.
	 */
	void setProperties(Dictionary newProps) {
		if (newProps != null) {
			String newConsPID = (String) newProps.get(WireConstants.WIREADMIN_CONSUMER_PID);
			String newProdPID = (String) newProps.get(WireConstants.WIREADMIN_PRODUCER_PID);

			String oldConsPID = (String) properties.get(WireConstants.WIREADMIN_CONSUMER_PID);
			String oldProdPID = (String) properties.get(WireConstants.WIREADMIN_PRODUCER_PID);

			boolean restart = false;

			if (newConsPID == null) {
				newProps.put(WireConstants.WIREADMIN_CONSUMER_PID, oldConsPID);
			} else if (!newConsPID.equals(oldConsPID)) {
				restart = true;
			}

			if (newProdPID == null) {
				newProps.put(WireConstants.WIREADMIN_PRODUCER_PID, oldProdPID);
			} else if (!newProdPID.equals(oldProdPID)) {
				restart = true;
			}
			// fix #1074
			if (newProps.get(WireConstants.WIREADMIN_PID) == null) {
				newProps.put(WireConstants.WIREADMIN_PID, properties.get(WireConstants.WIREADMIN_PID));
			}

			// WireProperties wprops = new WireProperties();
			properties.clear();

			for (Enumeration en = newProps.keys(); en.hasMoreElements();) {
				Object key = en.nextElement();
				properties.put0(key, newProps.get(key));
			}

			checkWireFilter();

			if (restart) {
				// One of the Consumer or Producer has been changed so restart
				// the wire
				stop();
				start();
				return;
			}
		}

		if (isConnected()) {
			informServices();
		}

		parent.notifyListeners(this, WireAdminEvent.WIRE_UPDATED, null);
	}

	private boolean isAcceptable(Object value) {
		Class[] flavors = getFlavors();

		if (flavors == null) {
			return true;
		}

		for (int i = 0; i < flavors.length; i++) {
			if (flavors[i].isInstance(value)) {
				return true;
			}
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.osgi.framework.ServiceListener#serviceChanged(org.osgi.framework.ServiceEvent)
	 */
	public void serviceChanged(ServiceEvent evt) {
		if (bc == null) {
			return;
		}
		switch (evt.getType()) {
			case ServiceEvent.REGISTERED :
				// System.out.println("E V E N T : registered");
				serviceRegistered(evt.getServiceReference());
				break;

			case ServiceEvent.UNREGISTERING :
				// System.out.println("E V E N T : unregistering");
				serviceUnregistered(evt.getServiceReference());
				break;

			case ServiceEvent.MODIFIED :
				// System.out.println("E V E N T : modified");
				serviceModified(evt.getServiceReference());
		}
	}

	String getWirePID() {
		return (String) properties.get(WireConstants.WIREADMIN_PID);
	}

	/**
	 * This method simply informs both Producer and Consumer with the methods
	 * consumersConnected, producersConnected if a wire bacames connected and
	 * the remaining service if the wire becames disconnected.
	 */
	private void informServices() {
		if (producerRef != null) {
			String producerPID = (String) properties.get(WireConstants.WIREADMIN_PRODUCER_PID);
			NotificationEvent ne = new NotificationEvent(producer, null, this, parent.getConnected(WireConstants.WIREADMIN_PRODUCER_PID, producerPID));
			parent.notifyConsumerProducer(ne);

			// try {
			// producer.consumersConnected(parent.getConnected(WireConstants.WIREADMIN_PRODUCER_PID,
			// producerPID));
			// } catch (Exception ex) {
			// parent.notifyListeners(this, WireAdminEvent.PRODUCER_EXCEPTION ,
			// ex);
			// }
		}

		if (consumerRef != null) {
			String consumerPID = (String) properties.get(WireConstants.WIREADMIN_CONSUMER_PID);
			NotificationEvent ne = new NotificationEvent(null, consumer, this, parent.getConnected(WireConstants.WIREADMIN_CONSUMER_PID, consumerPID));
			parent.notifyConsumerProducer(ne);

			// try {
			// consumer.producersConnected(parent.getConnected(WireConstants.WIREADMIN_CONSUMER_PID,
			// consumerPID));
			// } catch (Exception ex) {
			// parent.notifyListeners(this, WireAdminEvent.CONSUMER_EXCEPTION ,
			// ex);
			// }
		}
	}

}
