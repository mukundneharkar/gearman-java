package org.gearman.net;

import org.gearman.common.packets.Packet;
import org.gearman.exceptions.NoServersAvailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionPool {

    private final Logger LOG = LoggerFactory.getLogger(ConnectionPool.class);
    private final List<Connection> goodConnectionList;
    private final List<Connection> badConnectionList;
    private final AtomicInteger connectionIndex;
    private final Object connectionLock = new Object();

    public ConnectionPool()
    {
        this.goodConnectionList = new ArrayList();
        this.badConnectionList = new ArrayList();
        this.connectionIndex = new AtomicInteger(0);

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        Runnable checkDeadServers = new ConnectionChecker(this);
        executor.scheduleAtFixedRate(checkDeadServers, 0, 30, TimeUnit.SECONDS);

    }

    public void addConnection(Connection connection)
    {
        goodConnectionList.add(connection);
    }

    public boolean addHostPort(String hostName, int port)
    {
        goodConnectionList.add(new Connection(hostName, port));
        return true;
    }

    public void checkDeadConnections()
    {
        synchronized(connectionLock) {
            if(badConnectionList.size() != 0)
            {
                for(Iterator<Connection> it = badConnectionList.iterator(); it.hasNext(); )
                {
                    Connection c = it.next();
                    if(c.isHealthy())
                    {
                        LOG.info("Connection " + c.toString() + " is good again!");
                        goodConnectionList.add(c);
                        it.remove();
                    }
                }
            }
        }
    }

    public Connection getConnection() throws NoServersAvailableException
    {
        if(goodConnectionList.size() == 0)
        {
            checkDeadConnections();
        }
        Connection result = null;

        synchronized (connectionLock) {
            Connection connection = null;

            while(goodConnectionList.size() > 0)
            {
                // Simple round-robin for now, get the first one, if it fails, remove it
                // Otherwise, increment the counter to the next connection's index
                if(connectionIndex.get() >= goodConnectionList.size())
                {
                    connectionIndex.set(0);
                }

                connection = goodConnectionList.get(connectionIndex.getAndIncrement());

                // TODO: mask this with a presumption about last-time-seen to avoid these checks.
                if(connection.isHealthy())
                {
                    return connection;
                } else {
                    LOG.warn("Connection to " + connection.toString() + " is unhealthy, marking as bad.");
                    badConnectionList.add(connection);
                    goodConnectionList.remove(connection);
                }
            }
        }

        throw new NoServersAvailableException();
    }

    public void cleanup() {
        for(Connection c : goodConnectionList)
        {
            try {
                c.close();
            } catch (IOException ioe) {
                LOG.warn("Unable to close connection: " + ioe.toString());
            }
        }
    }

    public List<Connection> getGoodConnectionList()
    {
        return goodConnectionList;
    }
}

class ConnectionChecker implements Runnable
{
    private final ConnectionPool connectionPool;
    private final Logger LOG = LoggerFactory.getLogger(ConnectionChecker.class);

    public ConnectionChecker(ConnectionPool connectionPool)
    {
        this.connectionPool = connectionPool;
    }

    @Override
    public void run() {
        LOG.debug("Checking for previously bad connections we can revive.");
        connectionPool.checkDeadConnections();
    }
}


