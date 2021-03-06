/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server.rpc;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.accumulo.core.client.impl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.client.security.tokens.KerberosToken;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.conf.ConfigurationCopy;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.security.thrift.TCredentials;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TCredentialsUpdatingInvocationHandlerTest {

  TCredentialsUpdatingInvocationHandler<Object> proxy;
  ConfigurationCopy conf;

  @Before
  public void setup() {
    conf = new ConfigurationCopy();
    proxy = new TCredentialsUpdatingInvocationHandler<Object>(new Object(), conf);
  }

  @After
  public void teardown() {
    UGIAssumingProcessor.rpcPrincipal.set(null);
  }

  @Test
  public void testNoArgsAreIgnored() throws Exception {
    proxy.updateArgs(new Object[] {});
  }

  @Test
  public void testNoTCredsInArgsAreIgnored() throws Exception {
    proxy.updateArgs(new Object[] {new Object(), new Object()});
  }

  @Test
  public void testCachedTokenClass() throws Exception {
    final String principal = "root";
    ConcurrentHashMap<String,Class<? extends AuthenticationToken>> cache = proxy.getTokenCache();
    cache.clear();
    TCredentials tcreds = new TCredentials(principal, KerberosToken.CLASS_NAME, ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(principal);
    proxy.updateArgs(new Object[] {new Object(), tcreds});
    Assert.assertEquals(1, cache.size());
    Assert.assertEquals(KerberosToken.class, cache.get(KerberosToken.CLASS_NAME));
  }

  @Test(expected = ThriftSecurityException.class)
  public void testMissingPrincipal() throws Exception {
    final String principal = "root";
    TCredentials tcreds = new TCredentials(principal, KerberosToken.CLASS_NAME, ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(null);
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }

  @Test(expected = ThriftSecurityException.class)
  public void testMismatchedPrincipal() throws Exception {
    final String principal = "root";
    TCredentials tcreds = new TCredentials(principal, KerberosToken.CLASS_NAME, ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(principal + "foobar");
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }

  @Test(expected = ThriftSecurityException.class)
  public void testWrongTokenType() throws Exception {
    final String principal = "root";
    TCredentials tcreds = new TCredentials(principal, PasswordToken.class.getName(), ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(principal);
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }

  @Test
  public void testAllowedAnyImpersonationForAnyUser() throws Exception {
    final String proxyServer = "proxy";
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + proxyServer + ".users", "*");
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + proxyServer + ".hosts", "*");
    proxy = new TCredentialsUpdatingInvocationHandler<Object>(new Object(), conf);
    TCredentials tcreds = new TCredentials("client", KerberosToken.class.getName(), ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(proxyServer);
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }

  @Test
  public void testAllowedImpersonationForSpecificUsers() throws Exception {
    final String proxyServer = "proxy";
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + proxyServer + ".users", "client1,client2");
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + proxyServer + ".hosts", "*");
    proxy = new TCredentialsUpdatingInvocationHandler<Object>(new Object(), conf);
    TCredentials tcreds = new TCredentials("client1", KerberosToken.class.getName(), ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(proxyServer);
    proxy.updateArgs(new Object[] {new Object(), tcreds});
    tcreds = new TCredentials("client2", KerberosToken.class.getName(), ByteBuffer.allocate(0), UUID.randomUUID().toString());
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }

  @Test(expected = ThriftSecurityException.class)
  public void testDisallowedImpersonationForUser() throws Exception {
    final String proxyServer = "proxy";
    // let "otherproxy" impersonate, but not "proxy"
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + "otherproxy" + ".users", "*");
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + "otherproxy" + ".hosts", "*");
    proxy = new TCredentialsUpdatingInvocationHandler<Object>(new Object(), conf);
    TCredentials tcreds = new TCredentials("client", KerberosToken.class.getName(), ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(proxyServer);
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }

  @Test(expected = ThriftSecurityException.class)
  public void testDisallowedImpersonationForMultipleUsers() throws Exception {
    final String proxyServer = "proxy";
    // let "otherproxy" impersonate, but not "proxy"
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + "otherproxy1" + ".users", "*");
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + "otherproxy1" + ".hosts", "*");
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + "otherproxy2" + ".users", "client1,client2");
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + "otherproxy2" + ".hosts", "*");
    proxy = new TCredentialsUpdatingInvocationHandler<Object>(new Object(), conf);
    TCredentials tcreds = new TCredentials("client1", KerberosToken.class.getName(), ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(proxyServer);
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }

  @Test
  public void testAllowedImpersonationFromSpecificHost() throws Exception {
    final String proxyServer = "proxy", client = "client", host = "host.domain.com";
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + proxyServer + ".users", client);
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + proxyServer + ".hosts", host);
    proxy = new TCredentialsUpdatingInvocationHandler<Object>(new Object(), conf);
    TCredentials tcreds = new TCredentials("client", KerberosToken.class.getName(), ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(proxyServer);
    TServerUtils.clientAddress.set(host);
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }

  @Test(expected = ThriftSecurityException.class)
  public void testDisallowedImpersonationFromSpecificHost() throws Exception {
    final String proxyServer = "proxy", client = "client", host = "host.domain.com";
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + proxyServer + ".users", client);
    conf.set(Property.INSTANCE_RPC_SASL_PROXYUSERS.getKey() + proxyServer + ".hosts", host);
    proxy = new TCredentialsUpdatingInvocationHandler<Object>(new Object(), conf);
    TCredentials tcreds = new TCredentials("client", KerberosToken.class.getName(), ByteBuffer.allocate(0), UUID.randomUUID().toString());
    UGIAssumingProcessor.rpcPrincipal.set(proxyServer);
    // The RPC came from a different host than is allowed
    TServerUtils.clientAddress.set("otherhost.domain.com");
    proxy.updateArgs(new Object[] {new Object(), tcreds});
  }
}
