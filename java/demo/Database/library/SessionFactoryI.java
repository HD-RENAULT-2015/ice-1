// **********************************************************************
//
// Copyright (c) 2003-2008 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

import Demo.*;

class SessionFactoryI extends _SessionFactoryDisp
{
    public synchronized SessionPrx
    create(Ice.Current c)
    {
        SessionI session = new SessionI(_logger, c.adapter);
        _SessionTie servant = new _SessionTie(session);
        SessionPrx proxy = SessionPrxHelper.uncheckedCast(c.adapter.addWithUUID(servant));
        _logger.trace("SessionFactory", "create new session: " +
                      c.adapter.getCommunicator().identityToString(proxy.ice_getIdentity()));
        _reaper.add(proxy, session);
        return proxy;
    }

    SessionFactoryI(Ice.Logger logger, ReapThread reaper)
    {
        _logger = logger;
        _reaper = reaper;
    }

    private Ice.Logger _logger;
    private ReapThread _reaper;
}