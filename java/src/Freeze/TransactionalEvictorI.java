// **********************************************************************
//
// Copyright (c) 2003-2007 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

package Freeze;

class TransactionalEvictorI extends EvictorI implements TransactionalEvictor
{
    //
    // Must be in sync with Parser.cpp
    //
    static final int supports = 0;
    static final int mandatory = 1;
    static final int required = 2;
    static final int never = 3;

    public Transaction
    getCurrentTransaction()
    {
	_deactivateController.lock();
	try
	{
	    TransactionalEvictorContext ctx = _dbEnv.getCurrent();
	    return ctx == null ? null : ctx.transaction();
	}
	finally
	{
	    _deactivateController.unlock();
	}
    }
    
    public void
    setCurrentTransaction(Transaction tx)
    {
	_deactivateController.lock();
	try
	{
	    _dbEnv.setCurrentTransaction(tx);
	}
	finally
	{
	    _deactivateController.unlock();
	}
    }

    public Ice.ObjectPrx
    addFacet(Ice.Object servant, Ice.Identity ident, String facet)
    {
	checkIdentity(ident);
       
	if(facet == null)
	{
	    facet = "";
	}

	_deactivateController.lock();
	try
	{
	    long currentTime = IceInternal.Time.currentMonotonicTimeMillis();

	    ObjectRecord rec = new ObjectRecord(servant, new Statistics(currentTime, 0, 0));
	
	    ObjectStore store = findStore(facet, _createDb);
	    if(store == null)
	    {
		NotFoundException ex = new NotFoundException();
		ex.message = _errorPrefix + "addFacet: could not open database for facet '"
		    + facet + "'";
		throw ex;
	    }
       
	    TransactionI tx = beforeQuery();
	
	    updateStats(rec.stats, currentTime);

	    if(!store.insert(ident, rec, tx))
	    {
		Ice.AlreadyRegisteredException ex = new Ice.AlreadyRegisteredException();
		ex.kindOfObject = "servant";
		ex.id = _communicator.identityToString(ident);
		if(facet.length() > 0)
		{
		    ex.id += " -f " + IceUtil.StringUtil.escapeString(facet, "");
		}
		throw ex;
	    }

            if(_trace >= 1)
            {
                String objString = "object \"" + _communicator.identityToString(ident) + "\"";
                if(!facet.equals(""))
                {
                    objString += " with facet \"" + facet + "\"";
                }
                
                _communicator.getLogger().trace("Freeze.Evictor", "added " + objString + " to Db \"" + _filename +
                                                "\"");
            }

	    Ice.ObjectPrx obj = _adapter.createProxy(ident);
	    if(facet.length() > 0)
	    {
		obj = obj.ice_facet(facet);
	    }
	    return obj;
	}
	finally
	{
	    _deactivateController.unlock();
	}
    }

    public Ice.Object
    removeFacet(Ice.Identity ident, String facet)
    {
	checkIdentity(ident);
	if(facet == null)
	{
	    facet = "";
	}
	
	_deactivateController.lock();
	try
	{
	    Ice.Object servant = null;
	    boolean removed = false;

	    ObjectStore store = findStore(facet, false);
	    if(store != null)
	    {  
		TransactionalEvictorContext ctx = _dbEnv.getCurrent();
		TransactionI tx = null;
		if(ctx != null)
		{
		    tx = ctx.transaction();
		    if(tx == null)
		    {
			throw new DatabaseException(_errorPrefix + "inactive transaction");
		    }
		}

		removed = store.remove(ident, tx);
	    
		if(removed)
		{
		    if(ctx != null)
		    {
			//
			// Remove from cache when transaction commits
			//
			servant = ctx.servantRemoved(ident, store);
		    }
		    else
		    {
			//
			// Remove from cache immediately
			//
			servant = evict(ident, store);
		    }
		}
	    }

	    if(!removed)
	    {
		Ice.NotRegisteredException ex = new Ice.NotRegisteredException();
		ex.kindOfObject = "servant";
		ex.id = _communicator.identityToString(ident);
		if(facet.length() > 0)
		{
		    ex.id += " -f " + IceUtil.StringUtil.escapeString(facet, "");
		}
		throw ex;
	    }
	
	    if(_trace >= 1)
	    {
		String objString = "object \"" + _communicator.identityToString(ident) + "\"";
		if(!facet.equals(""))
		{
		    objString += " with facet \"" + facet + "\"";
		}
	    
		_communicator.getLogger().trace("Freeze.Evictor", "removed " + objString + " from Db \"" + _filename +
						"\"");
	    }
	    return servant;
	}
	finally
	{
	    _deactivateController.unlock();
	}
    }

    public boolean
    hasFacet(Ice.Identity ident, String facet)
    {
	checkIdentity(ident);
	if(facet == null)
	{
	    facet = "";
	}

	_deactivateController.lock();
	try
	{
	    ObjectStore store = findStore(facet, false);

	    if(store == null)
	    {
		return false;
	    }

	    TransactionI tx = beforeQuery();

	    if(tx == null)
	    {  
		EvictorElement element = (EvictorElement)store.cache().getIfPinned(ident);
		if(element != null)
		{
		    return true;
		}
	    
		return store.dbHasObject(ident, null);
	    }
	    else
	    {
		return store.dbHasObject(ident, tx);
	    }
	}
	finally
	{
	    _deactivateController.unlock();
	}
    }
    
    
    public void
    finished(Ice.Current current, Ice.Object servant, java.lang.Object cookieObj)
    {	     
	//
	// Nothing to do
	//
    }

    public void
    deactivate(String category)
    {
	if(_deactivateController.deactivate())
	{
	    try
	    {
		closeDbEnv();
	    }
	    finally
	    {
		_deactivateController.deactivationComplete();
	    }
	}
    }

    TransactionalEvictorI(Ice.ObjectAdapter adapter, String envName, com.sleepycat.db.Environment dbEnv, String filename, 
			  java.util.Map facetTypes, ServantInitializer initializer, Index[] indices, boolean createDb)
    {
	super(adapter, envName, dbEnv, filename, facetTypes, initializer, indices, createDb);
     
	String propertyPrefix = "Freeze.Evictor." + envName + '.' + filename; 
	_rollbackOnUserException = _communicator.getProperties().
	    getPropertyAsIntWithDefault(propertyPrefix + ".RollbackOnUserException", 0) != 0;
    }

    TransactionalEvictorI(Ice.ObjectAdapter adapter, String envName, String filename, 
			  java.util.Map facetTypes, ServantInitializer initializer, Index[] indices, boolean createDb)
    {
	this(adapter, envName, null, filename, facetTypes, initializer, indices, createDb);
    }

    //
    // The interceptor dispatch call
    //
    Ice.DispatchStatus 
    dispatch(Ice.Request request)
    {
	_deactivateController.lock();
	try
	{
	    Ice.Current current = request.getCurrent();

	    ObjectStore store = findStore(current.facet, false);
	    if(store == null)
	    {
		servantNotFound(current);
	    }
	    
	    TransactionalEvictorContext ctx = _dbEnv.getCurrent();

	    Ice.Object sample = store.sampleServant();
	    Ice.Object cachedServant = null;

            TransactionalEvictorContext.ServantHolder servantHolder = null;
            try
            {
                if(sample == null)
                {
                    if(ctx != null)
                    { 
                        try
                        {
                            servantHolder = ctx.createServantHolder(current, store);
                        }                    
                        catch(DeadlockException dx)
                        {
                            assert dx.tx == ctx.transaction();
                            ctx.deadlockException();
                            throw new TransactionalEvictorDeadlockException(dx.tx);
                        }
                        sample = servantHolder.servant();
                    }
                    else
                    {
                        //
                        // find / load read-only servant
                        //
                        
                        cachedServant = loadCachedServant(current.id, store);
                        
                        if(cachedServant == null)
                        {
                            servantNotFound(current);
                        }
                        sample = cachedServant;
                    }
                }
                
                assert sample != null;

                int operationAttributes = sample.ice_operationAttributes(current.operation);
                
                boolean readOnly = (_useNonmutating && current.mode == Ice.OperationMode.Nonmutating) 
                    || (!_useNonmutating && (operationAttributes & 0x1) == 0);
                
                int txMode = (operationAttributes & 0x6) >> 1;
                
                boolean ownCtx = false;
                
                //
                // Establish the proper context
                //
                switch(txMode)
                {
                    case never:
                    {
                        assert readOnly;
                        if(ctx != null)
                        {
                            throw new DatabaseException(_errorPrefix + "transaction rejected by 'never' metadata");
                        }
                        break;
                    }
                    case supports:
                    {
                        assert readOnly;
                        break;
                    }
                    case mandatory:
                    {
                        if(ctx == null)
                        {
                            throw new DatabaseException(_errorPrefix + "operation with a mandatory transaction");
                        }
                        break;
                    }
                    case required:
                    {
                        if(ctx == null)
                        {
                            ownCtx = true;
                        }
                        break;
                    }
                    default:
                    {
                        assert false;
                    }
                }
                
                if(ctx == null && !ownCtx)
                {
                    //
                    // Read-only dispatch
                    //
                    assert readOnly;
                    if(cachedServant == null)
                    {
                        cachedServant = loadCachedServant(current.id, store);
                        
                        if(cachedServant == null)
                        {
                            servantNotFound(current);
                        }
                    }
                    return cachedServant.ice_dispatch(request, null);
                }
                else
                {
                    boolean tryAgain = false;
                    do
                    {
                        Transaction tx = null;

                        if(ownCtx)
                        {
                            ctx = _dbEnv.createCurrent();
                        }
 
                        tx = ctx.transaction();

                        try
                        {
                            try
                            {		    
                                TransactionalEvictorContext.ServantHolder sh;
                                if(servantHolder != null)
                                {
                                    assert !ownCtx;
                                    //
                                    // Adopt it
                                    //
                                    sh = servantHolder;
                                    servantHolder = null;
                                }
                                else
                                {
                                    sh = ctx.createServantHolder(current, store);
                                }
                                
                                if(sh.servant() == null)
                                {
                                    servantNotFound(current);
                                }

                                if(!readOnly)
                                {
                                    sh.markReadWrite();
                                }
                           
                                try
                                {
                                    Ice.DispatchStatus dispatchStatus = sh.servant().ice_dispatch(request, ctx);
                                    if(dispatchStatus == Ice.DispatchStatus.DispatchUserException && _rollbackOnUserException)
                                    {
                                        ctx.rollback();
                                    }
                                    if(dispatchStatus == Ice.DispatchStatus.DispatchAsync)
                                    {
                                        //
                                        // Can throw DeadlockException or TransactionalEvictorDeadlockException
                                        //
                                        ctx.checkDeadlockException();

                                        if(ctx.clearUserException() && _rollbackOnUserException)
                                        {
                                            ctx.rollback();
                                        }
                                    }
                                    return dispatchStatus;
                                }
                                catch(RuntimeException ex)
                                {
                                    ctx.rollback();
                                    throw ex;
                                }
                                finally
                                {
                                    sh.release();
                                    if(ownCtx)
                                    {
                                        ctx.commit();
                                    }
                                }
                            }
                            catch(DeadlockException dx)
                            {
                                if(dx.tx == tx)
                                {
                                    ctx.deadlockException();
                                }
                                throw dx;
                            }
                            finally
                            {
                                if(ownCtx)
                                {
                                    ctx.rollback();
                                }
                            }
                        }
                        catch(DeadlockException dx)
                        {
                            if(ownCtx && dx.tx == tx)
                            {
                                tryAgain = true;
                            }
                            else
                            {
                                throw new TransactionalEvictorDeadlockException(dx.tx);
                            }
                        }
                        catch(TransactionalEvictorDeadlockException dx)
                        {
                            if(ownCtx && dx.tx == tx)
                            {
                                tryAgain = true;
                            }
                            else
                            {
                                throw dx;
                            }
                        }
                        finally
                        {
                            if(ownCtx)
                            {
                                _dbEnv.setCurrentTransaction(null);
                            }
                        }
                    } while(tryAgain);
                }
            }
            finally
            {
                if(servantHolder != null)
                {
                    //
                    // Won't raise anything since it's read-only
                    //
                    servantHolder.release();
                }
            }
        
            //
            // Javac does not detect this can't be reached
            //
            assert(false);
            throw new Ice.OperationNotExistException();
        }
        finally
        {
            _deactivateController.unlock();
        }
    }

    synchronized Ice.Object 
    evict(Ice.Identity ident, ObjectStore store)
    {
	EvictorElement element = (EvictorElement)store.cache().unpin(ident);
	
	if(element != null)
	{
	    element.evict(false);
	    return element.servant;
	}
	return null;
    }

    protected Object 
    createEvictorElement(Ice.Identity ident, ObjectRecord rec, ObjectStore store)
    {
	return new EvictorElement(rec.servant, ident, store);
    }

    protected Ice.Object
    locateImpl(Ice.Current current, Ice.LocalObjectHolder cookie)
    {
	return _interceptor;
    }

    protected boolean
    hasAnotherFacet(Ice.Identity ident, String facet)
    {
	_deactivateController.lock();
	try
	{
	    java.util.Map storeMapCopy;
	    synchronized(this)
	    {
		storeMapCopy = new java.util.HashMap(_storeMap);
	    }		
	    
	    TransactionI tx = beforeQuery();
	    
	    java.util.Iterator p = storeMapCopy.entrySet().iterator();
	    while(p.hasNext())
	    {
		java.util.Map.Entry entry = (java.util.Map.Entry)p.next();
		
		//
		// Do not check this facet again
		//
		if(!facet.equals(entry.getKey()))
		{
		    ObjectStore store = (ObjectStore)entry.getValue();
		    
		    if(tx == null && store.cache().getIfPinned(ident) != null)
		    {
			return true;
		    }
		    
		    if(store.dbHasObject(ident, tx))
		    {
			return true;
		    }
		}
	    }
	    
	    return false;
	}
	finally
	{
	    _deactivateController.unlock();
	}
    }

    protected void
    evict()
    {	     
	assert Thread.holdsLock(this);

	while(_currentEvictorSize > _evictorSize)
	{
	    //
	    // Evict, no matter what!
	    //
	    EvictorElement element = (EvictorElement)_evictorList.getLast();
	    element.evict(true);
	}
    } 

    protected TransactionI
    beforeQuery()
    {
	TransactionalEvictorContext ctx = _dbEnv.getCurrent();
	TransactionI tx = null;
	if(ctx != null)
	{
	    tx = ctx.transaction();
	    if(tx == null)
	    {
		throw new DatabaseException(_errorPrefix + "inactive transaction");
	    }
	}

	return tx;
    }

    private void 
    servantNotFound(Ice.Current current)
    { 
	if(_trace >= 2)
	{
	    _communicator.getLogger().trace("Freeze.Evictor", "could not find \"" 
					    + _communicator.identityToString(current.id) +"\" with facet \"" + current.facet + "\"");
	}

	if(hasAnotherFacet(current.id, current.facet))
	{
	    throw new Ice.FacetNotExistException(current.id, current.facet, current.operation);
	}
	else
	{
	    throw new Ice.ObjectNotExistException(current.id, current.facet, current.operation);
	}
    }

    private Ice.Object
    loadCachedServant(Ice.Identity ident, ObjectStore store)
    {
	for(;;)
	{
	    EvictorElement element = (EvictorElement)store.cache().pin(ident);
	    
	    if(element == null)
	    {
		return null;
	    }

	    synchronized(this)
	    {
		if(element.stale)
		{
		    //
		    // try again
		    //
		    continue;
		}

		element.fixEvictPosition();
		
		//
		// if _evictorSize is 0, I may evict myself ... no big deal
		//
		evict();

                if(_trace >= 3)
                {
                    _communicator.getLogger().trace("Freeze.Evictor", "loaded \"" 
                                                    + _communicator.identityToString(ident) +"\" with facet \"" + store.facet() 
                                                    + "\" into the cache");
                }
		return element.servant;
	    }
	}
    }
   
    private class EvictorElement
    {
	EvictorElement(Ice.Object servant, Ice.Identity identity, ObjectStore store)
	{
	    this.servant = servant;
	    _identity = identity;
	    _store = store;
	}
	
	void
	evict(boolean unpin)
	{
	    assert Thread.holdsLock(TransactionalEvictorI.this);
	    assert stale == false;
	    stale = true;

	    if(unpin)
	    {
		_store.cache().unpin(_identity);
	    }

	    if(_evictPosition != null)
	    {
		_evictPosition.remove();
		_evictPosition = null;
		_currentEvictorSize--;
	    }
	    else
	    {
		assert(!unpin);
	    }
	}

	void
	fixEvictPosition()
	{
	    assert Thread.holdsLock(TransactionalEvictorI.this);
	    assert stale == false;

	    if(_evictPosition == null)
	    {
		//
		// New element
		//
		_currentEvictorSize++;
	    }
	    else
	    {
		_evictPosition.remove();
	    }
	    _evictorList.addFirst(this);
	    _evictPosition = _evictorList.iterator();
	    //
	    // Position the iterator "on" the element.
	    //
	    _evictPosition.next();
	}


	final Ice.Object servant;

	//
	// Protected by the TransactionEvictorI mutex
	//
	boolean stale = false; // stale = true means no longer in the cache
	private java.util.Iterator _evictPosition;
    
	//
	// These two fields are only needed for eviction
	//
	final private ObjectStore _store;
	final private Ice.Identity _identity;
    }


    //
    // List of EvictorElement with stable iterators
    //
    private final Freeze.LinkedList _evictorList = new Freeze.LinkedList();
    private int _currentEvictorSize = 0;

    //
    // A simple adapter
    //
    private Ice.DispatchInterceptor _interceptor = new Ice.DispatchInterceptor()
	{
	    public Ice.DispatchStatus 
	    dispatch(Ice.Request request)
	    {
		return TransactionalEvictorI.this.dispatch(request);
	    }
	};

    private boolean _rollbackOnUserException;
}
