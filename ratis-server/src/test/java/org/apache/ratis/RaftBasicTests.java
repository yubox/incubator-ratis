/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis;

import org.apache.ratis.RaftTestUtil.SimpleMessage;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.impl.BlockRequestHandlingInjection;
import org.apache.ratis.server.impl.RaftServerImpl;
import org.apache.ratis.server.storage.RaftLog;
import org.junit.*;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.ratis.RaftTestUtil.logEntriesContains;
import static org.apache.ratis.RaftTestUtil.waitAndKillLeader;
import static org.apache.ratis.RaftTestUtil.waitForLeader;
import static org.apache.ratis.util.Preconditions.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class RaftBasicTests {
  public static final Logger LOG = LoggerFactory.getLogger(RaftBasicTests.class);

  public static final int NUM_SERVERS = 5;

  protected static final RaftProperties properties = new RaftProperties();

  public abstract MiniRaftCluster getCluster();

  public RaftProperties getProperties() {
    return properties;
  }

  @Rule
  public Timeout globalTimeout = new Timeout(120 * 1000);

  @Before
  public void setup() throws IOException {
    Assert.assertNull(getCluster().getLeader());
    getCluster().start();
  }

  @After
  public void tearDown() {
    final MiniRaftCluster cluster = getCluster();
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testBasicLeaderElection() throws Exception {
    LOG.info("Running testBasicLeaderElection");
    final MiniRaftCluster cluster = getCluster();
    waitAndKillLeader(cluster, true);
    waitAndKillLeader(cluster, true);
    waitAndKillLeader(cluster, true);
    waitAndKillLeader(cluster, false);
  }

  @Test
  public void testBasicAppendEntries() throws Exception {
    LOG.info("Running testBasicAppendEntries");
    final MiniRaftCluster cluster = getCluster();
    RaftServerImpl leader = waitForLeader(cluster);
    final long term = leader.getState().getCurrentTerm();
    final RaftPeerId killed = cluster.getFollowers().get(3).getId();
    cluster.killServer(killed);
    LOG.info(cluster.printServers());

    final SimpleMessage[] messages = SimpleMessage.create(10);
    try(final RaftClient client = cluster.createClient(null)) {
      for (SimpleMessage message : messages) {
        client.send(message);
      }
    }

    Thread.sleep(cluster.getMaxTimeout() + 100);
    LOG.info(cluster.printAllLogs());

    cluster.getServerAliveStream()
        .map(s -> s.getState().getLog())
        .forEach(log -> RaftTestUtil.assertLogEntries(log,
            log.getEntries(1, Long.MAX_VALUE), 1, term, messages));
  }

  @Test
  public void testOldLeaderCommit() throws Exception {
    LOG.info("Running testOldLeaderCommit");
    final MiniRaftCluster cluster = getCluster();
    RaftTestUtil.waitForLeader(cluster);
    final RaftPeerId leaderId = waitForLeader(cluster).getId();

    List<RaftServerImpl> followers = cluster.getFollowers();
    final RaftServerImpl followerToCommit = followers.get(0);
    for (int i = 1; i < NUM_SERVERS - 1; i++) {
      RaftServerImpl follower = followers.get(i);
      cluster.killServer(follower.getId());
    }

    SimpleMessage[] messages = SimpleMessage.create(1);
    try(final RaftClient client = cluster.createClient(null)) {
      for (SimpleMessage message: messages) {
        client.send(message);
      }
    }

    Thread.sleep(cluster.getMaxTimeout() + 100);
    logEntriesContains(followerToCommit.getState().getLog(), messages);

    BlockRequestHandlingInjection.getInstance().blockReplier(leaderId.toString());
    RaftTestUtil.setBlockRequestsFrom(leaderId.toString(), true);
    for (int i = 1; i < 3; i++) {
      RaftServerImpl follower = followers.get(i);
      cluster.restartServer(follower.getId(), false );
    }
    waitForLeader(cluster);
    Thread.sleep(cluster.getMaxTimeout() + 100);

    cluster.getServerAliveStream()
            .map(s -> s.getState().getLog())
            .forEach(log -> assertTrue(logEntriesContains(log, messages)));
  }

  @Test
  public void testOldLeaderNotCommit() throws Exception {
    LOG.info("Running testOldLeaderNotCommit");
    final MiniRaftCluster cluster = getCluster();
    RaftTestUtil.waitForLeader(cluster);
    final RaftPeerId leaderId = waitForLeader(cluster).getId();

    List<RaftServerImpl> followers = cluster.getFollowers();
    final RaftServerImpl followerToCommit = followers.get(0);
    for (int i = 1; i < NUM_SERVERS - 1; i++) {
      RaftServerImpl follower = followers.get(i);
      cluster.killServer(follower.getId());
    }

    SimpleMessage[] messages = SimpleMessage.create(1);
    try(final RaftClient client = cluster.createClient(null)) {
      for (SimpleMessage message: messages) {
        client.send(message);
      }
    }

    Thread.sleep(cluster.getMaxTimeout() + 100);
    logEntriesContains(followerToCommit.getState().getLog(), messages);

    cluster.killServer(leaderId);
    cluster.killServer(followerToCommit.getId());

    for (int i = 1; i < NUM_SERVERS - 1; i++) {
      RaftServerImpl follower = followers.get(i);
      cluster.restartServer(follower.getId(), false );
    }
    waitForLeader(cluster);
    Thread.sleep(cluster.getMaxTimeout() + 100);

    cluster.getServerAliveStream()
            .map(s -> s.getState().getLog())
            .forEach(log -> assertFalse(logEntriesContains(log, messages)));
  }

  @Test
  public void testEnforceLeader() throws Exception {
    LOG.info("Running testEnforceLeader");
    final String leader = "s" + ThreadLocalRandom.current().nextInt(NUM_SERVERS);
    LOG.info("enforce leader to " + leader);
    final MiniRaftCluster cluster = getCluster();
    waitForLeader(cluster);
    waitForLeader(cluster, leader);
  }

  static class Client4TestWithLoad extends Thread {
    final RaftClient client;
    final SimpleMessage[] messages;

    final AtomicInteger step = new AtomicInteger();
    volatile Exception exceptionInClientThread;

    Client4TestWithLoad(RaftClient client, int numMessages) {
      this.client = client;
      this.messages = SimpleMessage.create(numMessages, client.getId().toString());
    }

    boolean isRunning() {
      return step.get() < messages.length && exceptionInClientThread == null;
    }

    @Override
    public void run() {
      try {
        for (; isRunning(); ) {
          client.send(messages[step.getAndIncrement()]);
        }
        client.close();
      } catch (IOException ioe) {
        exceptionInClientThread = ioe;
      }
    }
  }

  @Test
  public void testWithLoad() throws Exception {
    testWithLoad(10, 500);
  }

  private void testWithLoad(final int numClients, final int numMessages)
      throws Exception {
    LOG.info("Running testWithLoad: numClients=" + numClients
        + ", numMessages=" + numMessages);

    final MiniRaftCluster cluster = getCluster();
    LOG.info(cluster.printServers());

    final List<Client4TestWithLoad> clients
        = Stream.iterate(0, i -> i+1).limit(numClients)
        .map(i -> cluster.createClient(null))
        .map(c -> new Client4TestWithLoad(c, numMessages))
        .collect(Collectors.toList());
    clients.forEach(Thread::start);

    int count = 0;
    for(int lastStep = 0;; ) {
      if (clients.stream().filter(Client4TestWithLoad::isRunning).count() == 0) {
        break;
      }

      final int n = clients.stream().mapToInt(c -> c.step.get()).sum();
      if (n - lastStep < 50 * numClients) { // Change leader at least 50 steps.
        Thread.sleep(10);
        continue;
      }
      lastStep = n;
      count++;

      RaftServerImpl leader = cluster.getLeader();
      if (leader != null) {
        final RaftPeerId oldLeader = leader.getId();
        LOG.info("Block all requests sent by leader " + oldLeader);
        RaftPeerId newLeader = RaftTestUtil.changeLeader(cluster, oldLeader);
        LOG.info("Changed leader from " + oldLeader + " to " + newLeader);
        assertFalse(newLeader.equals(oldLeader));
      }
    }

    for(Client4TestWithLoad c : clients) {
      c.join();
    }
    for(Client4TestWithLoad c : clients) {
      if (c.exceptionInClientThread != null) {
        throw new AssertionError(c.exceptionInClientThread);
      }
      RaftTestUtil.assertLogEntries(cluster.getServers(), c.messages);
    }

    LOG.info("Leader change count=" + count + cluster.printAllLogs());
  }
}
