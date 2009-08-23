/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.sync.api.db.hibernate;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.CallbackException;
import org.hibernate.EmptyInterceptor;
import org.hibernate.LazyInitializationException;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.collection.PersistentSet;
import org.hibernate.criterion.Expression;
import org.hibernate.criterion.Projections;
import org.hibernate.engine.ForeignKeys;
import org.hibernate.metadata.ClassMetadata;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.Type;
import org.openmrs.OpenmrsObject;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.NativeIfNotAssignedIdentityGenerator;
import org.openmrs.module.sync.SyncException;
import org.openmrs.module.sync.SyncItem;
import org.openmrs.module.sync.SyncItemKey;
import org.openmrs.module.sync.SyncItemState;
import org.openmrs.module.sync.SyncRecord;
import org.openmrs.module.sync.SyncRecordState;
import org.openmrs.module.sync.SyncUtil;
import org.openmrs.module.sync.api.SyncService;
import org.openmrs.module.sync.serialization.DefaultNormalizer;
import org.openmrs.module.sync.serialization.Item;
import org.openmrs.module.sync.serialization.Normalizer;
import org.openmrs.module.sync.serialization.Package;
import org.openmrs.module.sync.serialization.Record;
import org.openmrs.module.sync.serialization.TimestampNormalizer;
import org.openmrs.module.sync.SyncConstants;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Implements 'change interception' for data synchronization feature using
 * Hibernate interceptor mechanism. Intercepted changes are recorded into the
 * synchronization journal table in DB.
 * <p>
 * For detailed technical discussion see feature technical documentation on
 * openmrs.org.
 * 
 * @see org.hibernate.EmptyInterceptor
 */
public class HibernateSyncInterceptor extends EmptyInterceptor
        implements ApplicationContextAware {

	/**
	 * Helper container class to store type/value tuple for a given object
	 * property. Utilized during serializtion of intercepted entity changes.
	 * 
	 * @see HibernateSyncInterceptor#packageObject(OpenmrsObject,
	 *      Object[], String[], Type[], SyncItemState)
	 */
	protected class PropertyClassValue {
		String clazz, value;

		public String getClazz() {
			return clazz;
		}

		public String getValue() {
			return value;
		}

		public PropertyClassValue(String clazz, String value) {
			this.clazz = clazz;
			this.value = value;
		}
	}

	/**
	 * From Spring docs: There might be a single instance of Interceptor for a
	 * SessionFactory, or a new instance might be specified for each Session.
	 * Whichever approach is used, the interceptor must be serializable if the
	 * Session is to be serializable. This means that SessionFactory-scoped
	 * interceptors should implement readResolve().
	 */
	private static final long serialVersionUID = -4905755656754047400L;

	protected final Log log = LogFactory.getLog(HibernateSyncInterceptor.class);

	protected SyncService synchronizationService = null;

	/*
	 * App context. This is needed to retrieve an instance of current Spring
	 * SessionFactory. There should be a better way to do this but we
	 * collectively couldn't find one.
	 */
	private ApplicationContext context;

	static DefaultNormalizer defN = new DefaultNormalizer();
	static TimestampNormalizer tsN = new TimestampNormalizer();

	static final String sp = "_";

	// safetypes are *hibernate* types that we know how to serialize with help
	// of Normalizers
	static final Map<String, Normalizer> safetypes;
	static {
		safetypes = new HashMap<String, Normalizer>();
		// safetypes.put("binary", defN);
		// blob
		safetypes.put("boolean", defN);
		// safetypes.put("big_integer", defN);
		// safetypes.put("big_decimal", defN);
		// safetypes.put("byte", defN);
		// celendar
		// calendar_date
		// character
		// clob
		// currency
		// date
		// dbtimestamp
		safetypes.put("double", defN);
		safetypes.put("float", defN);
		safetypes.put("integer", defN);
		safetypes.put("locale", defN);
		safetypes.put("long", defN);
		safetypes.put("short", defN);
		safetypes.put("string", defN);
		safetypes.put("text", defN);
		safetypes.put("timestamp", tsN);
		// time
		// timezone
	}

	private ThreadLocal<SyncRecord> syncRecordHolder = new ThreadLocal<SyncRecord>();
	private ThreadLocal<Boolean> deactivated = new ThreadLocal<Boolean>();
	private ThreadLocal<HashSet<Object>> pendingFlushHolder = new ThreadLocal<HashSet<Object>>();

	public HibernateSyncInterceptor() {
		log.info("Initializing the synchronization interceptor");
	}

	/**
	 * Deactivates synchronization. Will be reset on transaction completion or
	 * manually.
	 */
	public void deactivateTransactionSerialization() {
		deactivated.set(true);
	}

	/**
	 * Re-activates synchronization.
	 */
	public void activateTransactionSerialization() {
		deactivated.remove();
	}

	/**
	 * Intercepts the start of a transaction. A new SyncRecord is created for
	 * this transaction/ thread to keep track of changes done during the
	 * transaction. Kept ThreadLocal.
	 * 
	 * @see org.hibernate.EmptyInterceptor#afterTransactionBegin(org.hibernate.Transaction)
	 */
	@Override
	public void afterTransactionBegin(Transaction tx) {
		if (log.isDebugEnabled())
			log.debug("afterTransactionBegin: " + tx + " deactivated: "
			        + deactivated.get());

		if (syncRecordHolder.get() != null) {
			log.warn("Replacing existing SyncRecord in SyncRecord holder");
		}

		syncRecordHolder.set(new SyncRecord());
	}

	/**
	 * Intercepts right before a commit is done. Not called in case of a
	 * rollback pr. Hibernate documentation. If synchronization is not disabled
	 * for this transaction/thread the SyncRecord kept ThreadLocal will be saved
	 * to the database, if it contains changes (SyncItems).
	 * 
	 * @see org.hibernate.EmptyInterceptor#beforeTransactionCompletion(org.hibernate.Transaction)
	 */
	@Override
	public void beforeTransactionCompletion(Transaction tx) {
		if (log.isDebugEnabled())
			log.debug("beforeTransactionCompletion: " + tx + " deactivated: "
			        + deactivated.get());

		try {
			// If synchronization is NOT deactivated
			if (deactivated.get() == null) {
				SyncRecord record = syncRecordHolder.get();
				syncRecordHolder.remove();

				// Does this transaction contain any serialized changes?
				if (record.hasItems()) {

					if (log.isDebugEnabled())
						log.debug(record.getItems().size()
						        + " SyncItems in SyncRecord, saving!");

					// Grab user if we have one, and use the GUID of the user as
					// creator of this SyncRecord
					User user = Context.getAuthenticatedUser();
					if (user != null) {
						record.setCreator(user.getUuid());
					}

					// Grab database version
					record.setDatabaseVersion(Context.getAdministrationService()
					                                 .getGlobalProperty("database_version"));

					// Complete the record
					record.setUuid(UUID.randomUUID().toString());
					if (record.getOriginalUuid() == null) {
						if (log.isInfoEnabled())
							log.info("OriginalUuid is null, so assigning a new GUID: " + record.getUuid());
						record.setOriginalUuid(record.getUuid());
					} else {
						if (log.isInfoEnabled())
							log.info("OriginalUuid is: "
							        + record.getOriginalUuid());
					}
					record.setState(SyncRecordState.NEW);
					record.setTimestamp(new Date());
					record.setRetryCount(0);

					// Save SyncRecord
					if (synchronizationService == null) {
						synchronizationService = Context.getService(SyncService.class);
					}

					synchronizationService.createSyncRecord(record,
					                                        record.getOriginalUuid());
				} else {
					// note: this will happen all the time with read-only
					// transactions
					if (log.isDebugEnabled())
						log.debug("No SyncItems in SyncRecord, save discarded (note: maybe a read-only transaction)!");
				}
			}
		} catch (Exception ex) {
			log.error("Journal error\n", ex);
			throw (new SyncException("Error in interceptor, see log messages and callstack.", ex));
		}
	}

	/**
	 * Intercepts after the transaction is completed, also called on rollback.
	 * Clean up any remaining ThreadLocal objects/reset.
	 * 
	 * @see org.hibernate.EmptyInterceptor#afterTransactionCompletion(org.hibernate.Transaction)
	 */
	@Override
	public void afterTransactionCompletion(Transaction tx) {
		if (log.isDebugEnabled())
			log.debug("afterTransactionCompletion: " + tx + " committed: "
			        + tx.wasCommitted() + " rolledback: " + tx.wasRolledBack()
			        + " deactivated: " + deactivated.get());

		// clean out SyncRecord in case of rollback:
		syncRecordHolder.remove();

		// reactivate the interceptor
		deactivated.remove();
	}

	/**
	 * Packages up deletes and sets the item state to DELETED.
	 * 
	 * @see #packageObject(OpenmrsObject, Object[], String[], Type[],
	 *      Serializable, SyncItemState)
	 */
	@Override
	public void onDelete(Object entity, Serializable id, Object[] state,
	        String[] propertyNames, Type[] types) {

		if (log.isInfoEnabled()) {
			log.info("onDelete: " + entity.getClass().getName());
		}

		// first see if entity should be written to the journal at all
		if (!this.shouldSynchronize(entity)) {
			if (log.isDebugEnabled())
				log.debug("Determined entity not to be journaled, exiting onDelete.");
			return;
		}

		// create new flush holder if needed
		if (pendingFlushHolder.get() == null) {
			pendingFlushHolder.set(new HashSet<Object>());
		}

		//add to flush holder: i.e. indicate there is something to be processed
		if (!pendingFlushHolder.get().contains(entity)) {
			pendingFlushHolder.get().add(entity);
		}
		
		//now package
		packageObject((OpenmrsObject) entity,
			              state,
			              propertyNames,
			              types,
			              id,
			              SyncItemState.DELETED);
		
		return;

	}

	/**
	 * Called before an object is saved. Triggers in our case for new objects
	 * (inserts)
	 * 
	 * Packages up the changes and sets item state to NEW.
	 * 
	 * @return false if data is unmodified by this interceptor, true if
	 *         modified. Adding GUIDs to new objects that lack them.
	 * 
	 * @see org.hibernate.EmptyInterceptor#onSave(java.lang.Object,
	 *      java.io.Serializable, java.lang.Object[], java.lang.String[],
	 *      org.hibernate.type.Type[])
	 */
	@Override
	public boolean onSave(Object entity, Serializable id, Object[] state,
	        String[] propertyNames, Type[] types) {
		if (log.isDebugEnabled())
			log.debug("onSave: " + state.toString());

		// Set the originating uuid for the sync record 
	 	setOriginalUuid(entity);
	 	
		// first see if entity should be written to the journal at all
		if (!this.shouldSynchronize(entity)) {
			if (log.isDebugEnabled()) {
				log.debug("Determined entity not to be journaled, exiting onSave.");
			}
		} else {

			// create new flush holder if needed
			if (pendingFlushHolder.get() == null)
				pendingFlushHolder.set(new HashSet<Object>());
	
			if (!pendingFlushHolder.get().contains(entity)) {
				pendingFlushHolder.get().add(entity);
			}
		
			packageObject((OpenmrsObject) entity,
					                     state,
					                     propertyNames,
					                     types,
					                     id,
					                     SyncItemState.NEW);
		}
		
		// we didn't modify the object, so return false
		return false;
	}

	/**
	 * Called before an object is updated in the database.
	 * 
	 * Packages up the changes and sets sync state to NEW for any objects we
	 * care about synchronizing.
	 * 
	 * @return false if data is unmodified by this interceptor, true if
	 *         modified. Adding GUIDs to new objects that lack them.
	 * 
	 * @see org.hibernate.EmptyInterceptor#onFlushDirty(java.lang.Object,
	 *      java.io.Serializable, java.lang.Object[], java.lang.Object[],
	 *      java.lang.String[], org.hibernate.type.Type[])
	 */
	@Override
	public boolean onFlushDirty(Object entity, Serializable id,
	        Object[] currentState, Object[] previousState,
	        String[] propertyNames, Type[] types) {
		if (log.isDebugEnabled())
			log.debug("onFlushDirty: " + entity.getClass().getName());

		// Set the originating uuid for the sync record 
	 	setOriginalUuid(entity);

		// first see if entity should be written to the journal at all
		if (!this.shouldSynchronize(entity)) {
			if (log.isDebugEnabled())
				log.debug("Determined entity not to be journaled, exiting onFlushDirty.");
		} else {
			/*
			 * NOTE: Accomodate Hibernate auto-flush semantics (as best as we
			 * understand them): In case of sync ingest: When processing SyncRecord
			 * with >1 sync item via ProcessSyncRecord() on parent, calls to get
			 * object/update object by uuid may cause auto-flush of pending updates;
			 * this would result in redundant sync items within a sync record. Use
			 * threadLocal HashSet to only keep one instance of dirty object for
			 * single hibernate flush. Note that this is (i.e. incurring
			 * autoflush()) is not normally observed in rest of openmrs service
			 * layer since most of the data change calls are encapsulated in single
			 * transactions.
			 */

			// create new holder if needed
			if (pendingFlushHolder.get() == null)
				pendingFlushHolder.set(new HashSet<Object>());

			if (!pendingFlushHolder.get().contains(entity)) {
				pendingFlushHolder.get().add(entity);
			}
			
			packageObject((OpenmrsObject) entity,
		                     currentState,
		                     propertyNames,
		                     types,
		                     id,
		                     SyncItemState.UPDATED);
			
		}
		
		// we didn't modify anything, so return false
		return false;
	}

	@Override
	public void postFlush(Iterator entities) {

		if (log.isDebugEnabled())
			log.debug("postFlush called.");

		// clear the holder
		pendingFlushHolder.remove();
	}

	/**
	 * Intercept prepared stmts for logging purposes only.
	 * <p>
	 * NOTE: At this point, we are ignoring any prepared statements. This method
	 * gets called on any prepared stmt; meaning selects also which makes
	 * handling this reliably difficult. Fundamentally, short of replaying sql
	 * as is on parent, it is diffucult to imagine safe and complete
	 * implementation.
	 * <p>
	 * Preferred approach is to weed out all dynamic SQL from openMRS DB layer
	 * and if absolutely necessary, create a hook for DB layer code to
	 * explicitely specify what SQL should be passed to the parent during
	 * synchronization.
	 * 
	 * @see org.hibernate.EmptyInterceptor#onPrepareStatement(java.lang.String)
	 */
	@Override
	public String onPrepareStatement(String sql) {
		if (log.isDebugEnabled())
			log.debug("onPrepareStatement. sql: " + sql);

		return sql;
	}

	/**
	 * Handles collection remove event. As can be seen in
	 * org.hibernate.engine.Collections, hibernate only calls remove when it is
	 * about to recreate a collection.
	 * 
	 * @see org.hibernate.engine.Collections.prepareCollectionForUpdate
	 * @see org.openmrs.api.impl.SyncIngestServiceImpl
	 */
	@Override
	public void onCollectionRemove(Object collection, Serializable key)
	        throws CallbackException {
		if (log.isDebugEnabled()) {
			log.debug("COLLECTION remove with key: " + key);
		}

		// this.processPersistentSet((PersistentSet)collection,key, "remove");
	}

	/**
	 * Handles collection recreate. Recreate is triggered by hibernate when
	 * collection object is replaced by new/different instance.
	 * <p>
	 * remarks: See hibernate AbstractFlushingEventListener and
	 * org.hibernate.engine.Collections implementation to understand how
	 * collection updates are hooked up in hibernate, specifically see
	 * Collections.prepareCollectionForUpdate().
	 * 
	 * @see org.hibernate.engine.Collections
	 * @see org.hibernate.event.def.AbstractFlushingEventListener
	 */
	@Override
	public void onCollectionRecreate(Object collection, Serializable key)
	        throws CallbackException {
		if (log.isDebugEnabled()) {
			log.debug("COLLECTION recreate with key: " + key);
		}

		if (!(collection instanceof org.hibernate.collection.PersistentSet)) {
			log.info("Cannot process collection that is not instance of PersistentSet, collection type was:"
			        + collection.getClass().getName());
			return;
		}

		this.processPersistentSet((PersistentSet) collection, key, "recreate");

	}

	/**
	 * Handles updates of a collection (i.e. added/removed entries).
	 * <p>
	 * remarks: See hibernate AbstractFlushingEventListener implementation to
	 * understand how collection updates are hooked up in hibernate.
	 * 
	 * @see org.hibernate.engine.Collections
	 * @see org.hibernate.event.def.AbstractFlushingEventListener
	 */
	@Override
	public void onCollectionUpdate(Object collection, Serializable key)
	        throws CallbackException {
		if (log.isDebugEnabled()) {
			log.debug("COLLECTION update with key: " + key);
		}

		if (!(collection instanceof org.hibernate.collection.PersistentSet)) {
			log.info("Cannot process collection that is not instance of PersistentSet, collection type was:"
			        + collection.getClass().getName());
			return;
		}

		this.processPersistentSet((PersistentSet) collection, key, "update");
	}
	
	/**
	 * Serializes and packages an intercepted change in object state.
	 * <p>
	 * IMPORTANT serialization notes:
	 * <p>
	 * Transient Properties. Transients are not serialized/journaled. Marking an
	 * object property as transient is the supported way of designating it as
	 * something not to be recorded into the journal.
	 * <p/>
	 * Hibernate Identity property. A property designated in Hibernate as
	 * identity (i.e. primary key) *is* not serialized. This is because sync
	 * does not enforce global uniqueness of database primary keys. Instead,
	 * custom uuid property is used. This allows us to continue to use native
	 * types for 'traditional' entity relationships.
	 * 
	 * @param entity The object changed.
	 * @param currentState Array containing data for each field in the object as
	 *        they will be saved.
	 * @param propertyNames Array containing name for each field in the object,
	 *        corresponding to currentState.
	 * @param types Array containing Type of the field in the object,
	 *        corresponding to currentState.
	 * @param state SyncItemState, e.g. NEW, UPDATED, DELETED
	 * @param id Value of the identifier for this entity
	 */
	protected void packageObject(OpenmrsObject entity,
	        Object[] currentState, String[] propertyNames, Type[] types,
	        Serializable id, SyncItemState state) throws SyncException {

		String objectUuid = null;
		String originalRecordUuid = null;
		Set<String> transientProps = null;
		String infoMsg = null;
		SessionFactory factory = null;

		ClassMetadata data = null;
		String idPropertyName = null;
		org.hibernate.tuple.IdentifierProperty idPropertyObj = null;

		// The container of values to be serialized:
		// Holds tuples of <property-name> -> {<property-type-name>,
		// <property-value as string>}
		HashMap<String, PropertyClassValue> values = new HashMap<String, PropertyClassValue>();

		try {
			
			//boolean isUuidAssigned = assignGUID(entity, currentState, propertyNames, state);
			objectUuid = entity.getUuid();

			// pull-out sync-network wide change id for the sync *record* (not
			// the entity itself),
			// if one was already assigned (i.e. this change is coming from some
			// other server)
			if (this.syncRecordHolder.get() != null) {
				originalRecordUuid = this.syncRecordHolder.get()
						.getOriginalUuid();
			} 

			// build up a starting msg for all logging:
			StringBuilder sb = new StringBuilder();
			sb.append("In PackageObject, entity type:");
			sb.append(entity.getClass().getName());
			sb.append(", entity uuid:");
			sb.append(objectUuid);
			sb.append(", originalUuid uuid:");
			sb.append(originalRecordUuid);
			infoMsg = sb.toString();

			if (log.isInfoEnabled())
				log.info(infoMsg);

			// Transient properties are not serialized.
			transientProps = new HashSet<String>();
			for (Field f : entity.getClass().getDeclaredFields()) {
				if (Modifier.isTransient(f.getModifiers())) {
					transientProps.add(f.getName());
					if (log.isInfoEnabled())
						log.info("The field " + f.getName()
						        + " is transient - so we won't serialize it");
				}
			}

			/*
			 * Retrieve metadata for this type; we need to determine what is the
			 * PK field for this type. We need to know this since PK values are
			 * *not* journalled; values of primary keys are assigned where
			 * physical DB records are created. This is so to avoid issues with
			 * id collisions.
			 * 
			 * In case of <generator class="assigned" />, the Identifier
			 * property is already assigned value and needs to be journalled.
			 * Also, the prop will *not* be part of currentState,thus we need to
			 * pull it out with reflection/metadata.
			 */
			factory = (SessionFactory) this.context.getBean("sessionFactory");
			data = factory.getClassMetadata(entity.getClass());
			if (data.hasIdentifierProperty()) {
				idPropertyName = data.getIdentifierPropertyName();
				idPropertyObj = ((org.hibernate.persister.entity.AbstractEntityPersister) data).getEntityMetamodel()
				                                                                               .getIdentifierProperty();
				if (id != null && idPropertyObj.getIdentifierGenerator() != null
				        && (idPropertyObj.getIdentifierGenerator() instanceof org.hibernate.id.Assigned)) {
					// serialize value as string
					values.put(idPropertyName,
					           new PropertyClassValue(id.getClass().getName(),
					                                  id.toString()));
				}
			}

			/*
			 * Loop through all the properties/values and put in a hash for
			 * duplicate removal
			 */
			for (int i = 0; i < types.length; i++) {
				String typeName = types[i].getName();
				if (log.isDebugEnabled())
					log.debug("Processing, type: " + typeName + " Field: "
					        + propertyNames[i]);

				if (propertyNames[i].equals(idPropertyName)
				        && log.isInfoEnabled())
					log.info(infoMsg + ", Id for this class: " + idPropertyName
					        + " , value:" + currentState[i]);

				if (currentState[i] != null) {
					// is this the primary key or transient? if so, we don't
					// want to serialize
					if (propertyNames[i].equals(idPropertyName)
					        || ("personId".equals(idPropertyName) && "patientId".equals(propertyNames[i]))
					        || ("personId".equals(idPropertyName) && "userId".equals(propertyNames[i]))
					        || transientProps.contains(propertyNames[i])) {
						// if (log.isInfoEnabled())
						log.info("Skipping property ("
						        + propertyNames[i]
						        + ") because it's either the primary key or it's transient.");

					} else {

						Normalizer n;
						if ((n = safetypes.get(typeName)) != null) {
							// Handle safe types like
							// boolean/String/integer/timestamp via Normalizers
							values.put(propertyNames[i],
							           new PropertyClassValue(typeName,
							                                  n.toString(currentState[i])));
						}
						/*
						 * Not a safe type, check if the object implements the
						 * OpenmrsObject interface
						 */
						else if (currentState[i] instanceof OpenmrsObject) {
							OpenmrsObject childObject = (OpenmrsObject) currentState[i];
							// child objects are not always loaded if not
							// needed, so let's surround this with try/catch,
							// package only if need to
							String childUuid = null;
							try {
								childUuid = childObject.getUuid();
							} catch (LazyInitializationException e) {
								if (log.isWarnEnabled())
									log.warn("Attempted to package/serialize child object, but child object was not yet initialized (and thus was null)");
								if (types[i].getReturnedClass()
								            .equals(User.class)) {
									// Wait - do we still need to do this, now
									// that we have sync bidirectional?
									// If User objects are sync'ing, then why
									// can't these just be uuids?
									// IS THIS RELIABLE??!?
									log.warn("SUBSTITUTED AUTHENTICATED USER FOR ACTUAL USER");
									childUuid = Context.getAuthenticatedUser()
									                   .getUuid();
								} else {
									// TODO: abort here also?
									log.error("COULD NOT SUBSTITUTE AUTHENTICATED USER FOR ACTUAL USER");
								}
							} catch (Exception e) {
								log.error(infoMsg
								        + ", Could not find child object - object is null, therefore uuid is null");
								throw (e);
							}

							/*
							 * child object is OpenmrsObject but its uuid is
							 * null, final attempt: load via PK if PK value
							 * available common scenario: this can happen when
							 * people are saving object graphs that are (at
							 * least partially) manually constructed (i.e.
							 * setting concept on obs just by filling in
							 * conceptid without first fetching the full concept
							 * state from DB for perf. reasons
							 */
							if (childUuid == null) {
								childUuid = fetchUuid(childObject);
								if (log.isDebugEnabled()) {
									log.debug(infoMsg
									        + "Field was null, attempted to fetch uuid with the following results");
									log.debug("Field type:"
									        + childObject.getClass().getName()
									        + ",uuid:" + childUuid);
								}
							}

							if (childUuid != null) {
								values.put(propertyNames[i],
								           new PropertyClassValue(typeName,
								                                  childUuid));
							} else {
								String msg = infoMsg
								        + ", Field value should be synchronized, but uuid is null.  Field Type: "
								        + typeName + " Field Name: "
								        + propertyNames[i];
								log.error(msg);
								throw (new SyncException(msg));
							}
						} else {
							// state != null but it is not safetype or
							// implements OpenmrsObject: do not package and log
							// as info
							if (log.isInfoEnabled())
								log.info(infoMsg
								        + ", Field Type: "
								        + typeName
								        + " Field Name: "
								        + propertyNames[i]
								        + " is not safe or OpenmrsObject, skipped!");
						}
					}
				} else {
					// current state null -- skip
					if (log.isDebugEnabled())
						log.debug("Field Type: " + typeName + " Field Name: "
						        + propertyNames[i] + " is null, skipped");
				}
			}

			/*
			 * Now serialize the data identified and put in the value-map
			 */
			// Setup the serialization data structures to hold the state
			Package pkg = new Package();
			String className = entity.getClass().getName();
			Record xml = pkg.createRecordForWrite(className);
			Item entityItem = xml.getRootItem();

			// loop throgh the map of the properties that need to be serialized
			for (Map.Entry<String, PropertyClassValue> me : values.entrySet()) {
				String property = me.getKey();

				// if we are processing onDelete event all we need is uuid
				if ((state == SyncItemState.DELETED)
				        && (!"uuid".equals(property))) {
					continue;
				}

				try {
					PropertyClassValue pcv = me.getValue();
					appendRecord(xml,
					             entityItem,
					             property,
					             pcv.getClazz(),
					             pcv.getValue());
				} catch (Exception e) {
					String msg = "Could not append attribute. Error while processing property: "
					        + property;
					log.error(msg, e);
					throw (new SyncException(msg));
				}
			}

			values.clear(); // Be nice to GC

			/*
			 * Create SyncItem and store change in SyncRecord kept in
			 * ThreadLocal.
			 */
			SyncItem syncItem = new SyncItem();
			syncItem.setKey(new SyncItemKey<String>(objectUuid, String.class));
			syncItem.setState(state);
			syncItem.setContent(xml.toStringAsDocumentFragement());
			syncItem.setContainedType(entity.getClass());

			if (log.isDebugEnabled())
				log.debug("Adding SyncItem to SyncRecord");

			syncRecordHolder.get().addItem(syncItem);
			syncRecordHolder.get().addContainedClass(entity.getClass()
			                                               .getSimpleName());

			// set the originating uuid for the record: do this once per Tx;
			// else we may end up with empty string
			if (syncRecordHolder.get().getOriginalUuid() == null
			        || "".equals(syncRecordHolder.get().getOriginalUuid())) {
				syncRecordHolder.get().setOriginalUuid(originalRecordUuid);
			}
		} catch (SyncException ex) {
			log.error("Journal error\n", ex);
			throw (ex);
		} catch (Exception e) {
			log.error("Journal error\n", e);
			throw (new SyncException("Error in interceptor, see log messages and callstack.", e));
		}

		return;
	}

	/**
	 * Adds a property value to the existing serialization record as a string.
	 * <p>
	 * If data is null or empty string it will be skipped, no empty
	 * serialization items are written. In case of xml serialization, the data
	 * will be serialized as: &lt;property
	 * type='classname'&gt;data&lt;/property&gt;
	 * 
	 * @param xml record node to append to
	 * @param parent the pointer to the root parent node
	 * @param property new item name (in case of xml serialization this will be
	 *        child element name)
	 * @param classname type of the property, will be recorded as attribute
	 *        named 'type' on the child item
	 * @param data String content, in case of xml serialized as text node (i.e.
	 *        not CDATA)
	 * @throws Exception
	 */
	protected void appendRecord(Record xml, Item parent, String property,
	        String classname, String data) throws Exception {
		// if (data != null && data.length() > 0) {
		// this will break if we don't allow data.length==0 - some string values
		// are required NOT NULL, but can be blank
		if (data != null) {
			Item item = xml.createItem(parent, property);
			item.setAttribute("type", classname);
			xml.createText(item, data);
		}
	}

	/**
	 * Determines if entity is to be 'synchronized'. There are three ways this
	 * can happen:
	 * <p>
	 * 1. Sync status is globally set to disabled.
	 * <p>
	 * 2. Entity implements OpenmrsObject interface.
	 * <p>
	 * 3. Entity implements OpenmrsObjectInstance and IsOpenmrsObject is set
	 * to true
	 * <p>
	 * 4. Finally, interceptor supports manual override to suspend
	 * synchronization by setting the deactivated bit (see
	 * {@link #deactivateTransactionSerialization()}). This option is provided
	 * only for rare occasions when previous methods are not sufficient (i.e
	 * suspending interception in case of inline sql).
	 * 
	 * @param entity Object to examine.
	 * @return true if entity should be synchronized, else false.
	 * @see org.openmrs.synchronization.OpenmrsObject
	 * @see org.openmrs.synchronization.OpenmrsObjectInstance
	 */
	protected boolean shouldSynchronize(Object entity) {

		// OpenmrsObject *only*.
		if (!(entity instanceof OpenmrsObject)) {
			if (log.isDebugEnabled())
				log.debug("Do nothing. Flush with type that does not implement OpenmrsObject, type is:"
				        + entity.getClass().getName());
			return false;
		}
		
		//do not sync global properties
		if (entity instanceof org.openmrs.GlobalProperty) {
				return false;
		}

		// finally, if 'deactivated' bit was set manually, return accordingly
		if (deactivated.get() == null)
			return true;
		else
			return false;
	}

	/**
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	public void setApplicationContext(ApplicationContext context)
	        throws BeansException {
		this.context = context;
	}

	/**
	 * Retrieves uuid of OpenmrsObject instance from the storage based on
	 * indentity value (i.e. PK).
	 * 
	 * <p>
	 * Remarks: It is important for the implementation to
	 * avoid loading obj into session while trying to determine its uuid.
	 * As a result, the implementation uses the combination of reflection to determine
	 * the object's identifier value and Hibernate criteria in order to build
	 * select statement for getting the uuid.
	 * 
	 * The reason to avoid fetching the obj is because doing it causes an error 
	 * in hibernate when processing disconnected proxies. 
	 * Specifically, during obs edit, several properties are are
	 * disconnected as the form controller uses Criteria object to construct select
	 * queury session.clear() and then session.merge(). 
	 * 
	 * Finally, implementation suspends any state flushing to avoid any weird
	 * auto-flush events being triggered while select is being executed.
	 * 
	 * @param obj Instance of OpenmrsObject for which to retrieve uuid for.
	 * @return uuid from storage if obj identity value is set, else null.
	 * @see ForeignKeys
	 */
	protected String fetchUuid(OpenmrsObject obj) {
		String uuid = null;
		String idPropertyName = null;
		Object idPropertyValue = null;
		Method m = null;

		// what are you doing to me?!
		if (obj == null)
			return null;

		SessionFactory factory = null;
		org.hibernate.FlushMode flushMode = null;
		try {
			
			factory = (SessionFactory) this.context.getBean("sessionFactory");
			Class objTrueType = null;
			if (obj instanceof HibernateProxy) {
				objTrueType = org.hibernate.proxy.HibernateProxyHelper.getClassWithoutInitializingProxy(obj);
			} else {
				objTrueType = obj.getClass();
			}

			// ClassMetadata is only available for entities configured in
			// hibernate
			ClassMetadata data = factory.getClassMetadata(objTrueType);
			if (data != null) {
				idPropertyName = data.getIdentifierPropertyName();
				if (idPropertyName != null) {

					m = SyncUtil.getGetterMethod(objTrueType, idPropertyName);
					if (m != null) {
						idPropertyValue = m.invoke(obj, (Object[]) null);
					}
				}
			}

			//for time being, suspend any flushing
			flushMode = factory.getCurrentSession().getFlushMode();
			factory.getCurrentSession().setFlushMode(org.hibernate.FlushMode.MANUAL);
			
			// finally try to fetch the instance and get its uuid
			if (idPropertyValue != null) {
				//build sql to fetch uuid -  avoid loading obj into session
				org.hibernate.Criteria criteria = factory.getCurrentSession().createCriteria(objTrueType);
				criteria.add(Expression.idEq(idPropertyValue));
				criteria.setProjection(Projections.property("uuid"));
				Object uuidVal = criteria.uniqueResult();
				
				if (uuidVal != null) {
					uuid = (String)uuidVal;
				}

			}
		} catch (Exception ex) {
			// something went wrong - no matter just return null
			uuid = null;
			log.warn("Error in fetchUuid: returning null", ex);
		} finally {
			if (factory != null) {
				factory.getCurrentSession().setFlushMode(flushMode);
			}
		}

		return uuid;
	}

	/**
	 * Processes changes to persistent sets that contains instances of
	 * OpenmrsObject objects.
	 * 
	 * <p>
	 * Remarks:
	 * <p>
	 * Xml 'schema' for the sync item content for the persisted set follows.
	 * Note that for persisted sets syncItemKey is a composite of owner object
	 * uuid and the property name that contains the collection.
	 * <br/>&lt;persistent-set&gt; element: wrapper element <br/>&lt;owner
	 * uuid='' propertyName='' type='' action='recreate|update' &gt; element:
	 * this captures the information about the object that holds reference to
	 * the collection being processed <br/>-uuid: owner object uuid
	 * <br/>-properyName: names of the property on owner object that holds this
	 * collection <br/>-type: owner class name <br/>-action: recreate, update --
	 * these are collection events defined by hibernate interceptor
	 * <br/>&lt;entry action='update|delete' uuid='' type='' &gt; element: this
	 * captures info about individual collection entries: <br/>-action: what is
	 * being done to this item of the collection: delete (item was removed from
	 * the collection) or update (item was added to the collection) <br/>-uuid:
	 * entry's uuid <br/>-type: class name
	 * 
	 * @param set Instance of Hibernate PersistentSet to process.
	 * @param key key of owner for the set.
	 * @param action action being performed on the set: update, recreate
	 */
	protected void processPersistentSet(PersistentSet set, Serializable key,
	        String action) {
		OpenmrsObject owner = null;
		String originalRecordUuid = null;
		SessionFactory factory = null;
		LinkedHashMap<String, OpenmrsObject> entriesHolder = null;

		// we only process recreate and update
		if (!"update".equals(action) && !"recreate".equals(action)) {
			log.error("Unexpected 'action' supplied, valid values: recreate, update. value provided: "
			        + action);
			throw new CallbackException("Unexpected 'action' supplied while processing a persistent set.");
		}

		// retrieve owner and original uuid if there is one
		if (set.getOwner() instanceof OpenmrsObject) {
			owner = (OpenmrsObject) set.getOwner();
			if (this.syncRecordHolder.get() != null) { 
				originalRecordUuid = this.syncRecordHolder.get().getOriginalUuid(); 
			};
		} else {
			log.info("Cannot process PersistentSet where owner is not OpenmrsObject.");
			return;
		}

		factory = (SessionFactory) this.context.getBean("sessionFactory");

		/*
		 * determine if this set needs to be processed. Process if: 1. it is
		 * recreate or 2. is dirty && current state does not equal stored
		 * snapshot
		 */
		boolean process = false;
		if ("recreate".equals(action)) {
			process = true;
		} else {
			if (set.isDirty()) {
				org.hibernate.persister.collection.CollectionPersister persister = ((org.hibernate.engine.SessionFactoryImplementor) factory).getCollectionPersister(set.getRole());
                Object ss = null;
                try { //code around hibernate bug: http://opensource.atlassian.com/projects/hibernate/browse/HHH-2937
                      ss = set.getSnapshot(persister);
                } catch (NullPointerException ex) { }
                if (ss == null) {
                	log.debug("snapshot is null");
                	if (set.isEmpty())
                		process = false;
                	else
                		process = true;
                } else if (!set.equalsSnapshot(persister)) {
					process = true;
				};
			}
			
			if (!process) {
				log.info("set processing, no update needed: not dirty or current state and snapshots are same");
			}
		}
		if (!process)
			return;

		// pull out the property name on owner that corresponds to the
		// collection
		ClassMetadata data = factory.getClassMetadata(owner.getClass());
		String[] propNames = data.getPropertyNames();
		String ownerPropertyName = null; // this is the name of the property
											// on owner object that contains the
											// set
		for (String propName : propNames) {
			Object propertyVal = data.getPropertyValue(owner,
			                                           propName,
			                                           org.hibernate.EntityMode.POJO);
			// note: test both with equals() and == because
			// PersistentSet.equals()
			// actually does not handle equality of two persistent sets well
			if (set == propertyVal || set.equals(propertyVal)) {
				ownerPropertyName = propName;
				break;
			}
		}
		if (ownerPropertyName == null) {
			log.error("Could not find the property on owner object that corresponds to the set being processed.");
			log.error("owner info: \ntype: " + owner.getClass().getName()
			        + ", \nuuid: " + owner.getUuid()
			        + ",\n property name for collection: " + ownerPropertyName);
			throw new CallbackException("Could not find the property on owner object that corresponds to the set being processed.");
		}

		// Setup the serialization data structures to hold the state
		Package pkg = new Package();
		entriesHolder = new LinkedHashMap<String,OpenmrsObject>();
		try {

			// find out what entries need to be serialized
			for (Object entry : set) {
				if (entry instanceof OpenmrsObject) {
					OpenmrsObject obj = (OpenmrsObject) entry;

					// attempt to retrieve entry uuid
					String entryUuid = obj.getUuid();
					if (entryUuid == null) {
						entryUuid = fetchUuid(obj);
						if (log.isDebugEnabled()) {
							log.debug("Entry uuid was null, attempted to fetch uuid with the following results");
							log.debug("Entry type:" + obj.getClass().getName()
							        + ",uuid:" + entryUuid);
						}
					}
					// well, this is messed up: have an instance of
					// OpenmrsObject but has no uuid
					if (entryUuid == null) {
						log.error("Cannot handle set entries where uuid is null.");
						throw new CallbackException("Cannot handle set entries where uuid is null.");
					}
					
					//add it to the holder to avoid possible duplicates: key = uuid + action
					entriesHolder.put(entryUuid + "|update",obj);
				} else {
					// TODO: more debug info
					log.error("Cannot handle sets where entries are not OpenmrsObject!");
					throw new CallbackException("Cannot handle sets where entries are not OpenmrsObject!");
				}
			}

			// add on deletes
			if (!"recreate".equals(action) && set.getRole() != null) {
				org.hibernate.persister.collection.CollectionPersister persister = ((org.hibernate.engine.SessionFactoryImplementor) factory).getCollectionPersister(set.getRole());
				Iterator it = set.getDeletes(persister, false);
				if (it != null) {
					while (it.hasNext()) {
						Object entryDelete = it.next();
						if (entryDelete instanceof OpenmrsObject) {
							OpenmrsObject objDelete = (OpenmrsObject) entryDelete;
							// attempt to retrieve entry uuid
							String entryDeleteUuid = objDelete.getUuid();
							if (entryDeleteUuid == null) {
								entryDeleteUuid = fetchUuid(objDelete);
								if (log.isDebugEnabled()) {
									log.debug("Entry uuid was null, attempted to fetch uuid with the following results");
									log.debug("Entry type:"
									        + entryDeleteUuid.getClass().getName()
									        + ",uuid:" + entryDeleteUuid);
								}
							}
							// well, this is messed up: have an instance of
							// OpenmrsObject but has no uuid
							if (entryDeleteUuid == null) {
								log.error("Cannot handle set delete entries where uuid is null.");
								throw new CallbackException("Cannot handle set delete entries where uuid is null.");
							}

							//add it to the holder to avoid possible duplicates: key = uuid + action
							entriesHolder.put(entryDeleteUuid + "|delete",objDelete);
							
						} else {
							// TODO: more debug info
							log.error("Cannot handle sets where entries are not OpenmrsObject!");
							throw new CallbackException("Cannot handle sets where entries are not OpenmrsObject!");
						}
					}
				}
			}

			/*
			 * Create SyncItem and store change in SyncRecord kept in
			 * ThreadLocal. note: when making SyncItemKey, make it a composite
			 * string of uuid + prop. name to avoid collisions with updates to
			 * parent object or updates to more than one collection on same
			 * owner
			 */
			
			// Setup the serialization data structures to hold the state
			Record xml = pkg.createRecordForWrite(set.getClass().getName());
			Item entityItem = xml.getRootItem();

			// serialize owner info: we will need type, prop name where set
			// goes, and owner uuid
			Item item = xml.createItem(entityItem, "owner");
			item.setAttribute("type", this.getType(owner));
			item.setAttribute("properyName", ownerPropertyName);
			item.setAttribute("action", action);
			item.setAttribute("uuid", owner.getUuid());
			
			//build out the xml for the item content
			for( String entryKey : entriesHolder.keySet()) {
				OpenmrsObject entryObject = entriesHolder.get(entryKey);
				
				Item temp = xml.createItem(entityItem, "entry");
				temp.setAttribute("type", this.getType(entryObject));
				temp.setAttribute("action", entryKey.substring(entryKey.indexOf('|') + 1));
				temp.setAttribute("uuid", entryObject.getUuid());				
			}
			
			SyncItem syncItem = new SyncItem();
			syncItem.setKey(new SyncItemKey<String>(owner.getUuid() + "|"
			        + ownerPropertyName, String.class));
			syncItem.setState(SyncItemState.UPDATED);
			syncItem.setContainedType(set.getClass());
			syncItem.setContent(xml.toStringAsDocumentFragement());

			syncRecordHolder.get().addOrRemoveAndAddItem(syncItem);
			syncRecordHolder.get().addContainedClass(owner.getClass()
			                                              .getSimpleName());

			// do the original uuid dance, same as in packageObject
			if (syncRecordHolder.get().getOriginalUuid() == null
			        || "".equals(syncRecordHolder.get().getOriginalUuid())) {
				syncRecordHolder.get().setOriginalUuid(originalRecordUuid);
			}
		} catch (Exception ex) {
			log.error("Error processing Persistent set, see callstack and inner expection",
			          ex);
			throw new CallbackException("Error processing Persistent set, see callstack and inner expection.", ex);
		}
	}

	/**
	 * Returns string representation of type for given object. The main idea is to strip off the hibernate proxy info, if it happens to be present.
	 * 
	 * @param obj object 
	 * @return
	 */
	private String getType(Object obj) {
		
		//be defensive about it
		if (obj == null) {
			throw new CallbackException("Error trying to determine type for object; object is null.");
		}
		
		Object concreteObj = obj;
		if (obj instanceof org.hibernate.proxy.HibernateProxy) {
			concreteObj = ((HibernateProxy)obj).getHibernateLazyInitializer().getImplementation();
		}
	
		return concreteObj.getClass().getName();
	}

	/**
	 * Sets the originating uuid for the sync record. This is done once per Tx;
	 * else we may end up with empty string.
	 * 
	 * NOTE: This code is needed because we need for entity to know if it is
	 * genuine local change or it is coming from the ingest code. This is what
	 * original_uuid field in sync_journal is used for.
	 * 
	 * The way sync record uuids from ingest code are passed to the interceptor
	 * is by convention: the the *1st* thing ingest code will do when processing
	 * changes is to issue an update to global property with name
	 * SyncConstants.PROPERTY_ORIGINAL_UUID and passing the ingest record uuid
	 * as a value.
	 * 
	 * This is done so that the intercetor can pull out the 'original' record
	 * uuid associated with the change to the entity that is being processed.
	 * Since ingest and interceptor do not have direct reference to each other,
	 * there is no simple way to pass this info directly.
	 * 
	 * More: read javadoc on SyncRecord to see what SyncRecord.OriginalUuid is
	 * and how it is used.
	 * 
	 * @param entity
	 */
	private void setOriginalUuid(Object entity) {

		String originalRecordGuid = null;
		if (syncRecordHolder.get() != null) {
			if (syncRecordHolder.get().getOriginalUuid() != null) {
				originalRecordGuid = syncRecordHolder.get().getOriginalUuid();

			} else if (entity instanceof org.openmrs.GlobalProperty) {
				org.openmrs.GlobalProperty gp = (org.openmrs.GlobalProperty) entity;
				if (SyncConstants.PROPERTY_ORIGINAL_UUID.equals(gp
						.getProperty())) {
					// okay, this is original guid being passed in from ingest
					originalRecordGuid = gp.getPropertyValue();
					if (this.syncRecordHolder.get().getOriginalUuid() == null
							|| "".equals(syncRecordHolder.get()
									.getOriginalUuid())) {
						syncRecordHolder.get().setOriginalUuid(
								originalRecordGuid);
					}
				}
			}
		}
	} 	

}