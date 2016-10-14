/*
 * (C) Copyright 2012-2016 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Yannis JULIENNE
 *
 */

package org.nuxeo.launcher.connect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.FileEntity;
import org.apache.http.localserver.LocalServerTestBase;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.nuxeo.common.Environment;
import org.nuxeo.common.utils.ZipUtils;
import org.nuxeo.connect.NuxeoConnectClient;
import org.nuxeo.connect.update.PackageState;
import org.nuxeo.launcher.config.TomcatConfigurator;
import org.nuxeo.launcher.connect.fake.LocalConnectFakeConnector;

/**
 * @since 8.4
 */
public class TestConnectBroker extends LocalServerTestBase {

    final File testStore = new File("src/test/resources/packages/store");

    final File nuxeoHome = new File("target/launcher");

    @Before
    public void beforeEach() throws Exception {
        // set fake Connect connector
        String addonJSON = FileUtils.readFileToString(new File(testStore, "addon_remote.json"));
        String hotfixJSON = FileUtils.readFileToString(new File(testStore, "hotfix_remote.json"));
        String studioJSON = FileUtils.readFileToString(new File(testStore, "studio_remote.json"));
        NuxeoConnectClient.getConnectGatewayComponent().setTestConnector(
                new LocalConnectFakeConnector(addonJSON, hotfixJSON, studioJSON));

        // build env
        Environment.setDefault(null);
        FileUtils.deleteQuietly(nuxeoHome);
        nuxeoHome.mkdirs();
        System.setProperty(Environment.NUXEO_HOME, nuxeoHome.getPath());
        System.setProperty(TomcatConfigurator.TOMCAT_HOME, Environment.getDefault().getServerHome().getPath());

        // build test packages store
        buildInitialPackageStore();

        // start fake connect server for package downloads
        startFakeConnectServer();
    }

    private void buildInitialPackageStore() throws IOException {
        File nuxeoPackages = new File(nuxeoHome, "packages");
        File nuxeoStrore = new File(nuxeoPackages, "store");
        File uninstallFile = new File(testStore, "uninstall.xml");
        FileUtils.iterateFiles(testStore, new String[] { "zip" }, false).forEachRemaining(pkgZip -> {
            try {
                File pkgDir = new File(nuxeoStrore, pkgZip.getName().replace(".zip", ""));
                ZipUtils.unzip(pkgZip, pkgDir);
                FileUtils.copyFileToDirectory(uninstallFile, pkgDir);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        FileUtils.copyFileToDirectory(new File(testStore, ".packages"), nuxeoPackages);
    }

    private void startFakeConnectServer() throws Exception {
        serverBootstrap.registerHandler("/test/*", new HttpRequestHandler() {

            @Override
            public void handle(HttpRequest request, HttpResponse response, HttpContext context)
                    throws HttpException, IOException {
                String requestUri = request.getRequestLine().getUri();
                String pkgId = requestUri.substring(requestUri.lastIndexOf("/") + 1);
                File pkgZip = new File(testStore, pkgId + ".zip");
                if (pkgZip.exists()) {
                    HttpEntity entity = new FileEntity(pkgZip);
                    response.setEntity(entity);
                    response.setStatusCode(HttpStatus.SC_OK);
                } else {
                    response.setStatusCode(HttpStatus.SC_NOT_FOUND);
                }
            }
        });
        serverBootstrap.setListenerPort(8082);
        start();
    }

    @After
    public void afterEach() {
        // clear system properties
        System.clearProperty(Environment.NUXEO_HOME);
        System.clearProperty(TomcatConfigurator.TOMCAT_HOME);
    }

    @Test
    public void testInstallPackageRequest() throws Exception {
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_NAME, "server");
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_VERSION, "8.3");
        ConnectBroker connectBrocker = new ConnectBroker(Environment.getDefault());
        ((StandaloneCallbackHolder) NuxeoConnectClient.getCallBackHolder()).setTestMode(true);
        connectBrocker.setAllowSNAPSHOT(false);

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT", "A-1.2.0",
                        "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1", "B-1.0.2",
                        "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        // B-1.0.2 is not available on platform version server-8.3
        assertThat(connectBrocker.pkgRequest(null, Arrays.asList("A-1.2.0", "B-1.0.2"), null, null, true,
                false)).isFalse();

        // After: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT", "A-1.2.0",
                        "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1", "B-1.0.2",
                        "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        connectBrocker.setRelax("true");
        assertThat(
                connectBrocker.pkgRequest(null, Arrays.asList("A-1.2.0", "B-1.0.2"), null, null, true, false)).isTrue();

        // After: [studioA-1.0.0, hfA-1.0.0, A-1.2.0, B-1.0.2, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.2.0", "B-1.0.2", "C-1.0.0", "D-1.0.2-SNAPSHOT"),
                PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT", "A-1.0.0",
                        "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1-SNAPSHOT",
                        "B-1.0.1", "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        assertThat(connectBrocker.pkgRequest(null, Arrays.asList("A-1.2.2-SNAPSHOT", "C-1.0.2-SNAPSHOT"), null, null,
                true, false)).isTrue();

        // After: [studioA-1.0.0, hfA-1.0.0, A-1.2.2-SNAPSHOT, B-1.0.2, C-1.0.2-SNAPSHOT, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.2.2-SNAPSHOT", "B-1.0.2",
                "C-1.0.2-SNAPSHOT", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT", "A-1.0.0",
                        "A-1.2.1-SNAPSHOT", "A-1.2.0", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1-SNAPSHOT", "B-1.0.1",
                        "C-1.0.1-SNAPSHOT", "C-1.0.0", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);
    }

    @Test
    public void testReInstallPackageRequest() throws Exception {
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_NAME, "server");
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_VERSION, "8.3");
        ConnectBroker connectBrocker = new ConnectBroker(Environment.getDefault());
        ((StandaloneCallbackHolder) NuxeoConnectClient.getCallBackHolder()).setTestMode(true);
        connectBrocker.setAllowSNAPSHOT(false);

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT", "A-1.2.0",
                        "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1", "B-1.0.2",
                        "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        // A-1.0.0 is a release and is already installed
        assertThat(connectBrocker.pkgRequest(null, Arrays.asList("A-1.0.0"), null, null, true, false)).isTrue();

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT", "A-1.2.0",
                        "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1", "B-1.0.2",
                        "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        // B-1.0.1-SNAPSHOT must be uninstall then reinstall as it is a SNAPSHOT
        assertThat(
                connectBrocker.pkgRequest(null, Arrays.asList("B-1.0.1-SNAPSHOT"), null, null, true, false)).isTrue();

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT", "A-1.2.0",
                        "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1", "B-1.0.2",
                        "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);
    }

    @Test
    public void testUpgradePackageRequestWithRelax() throws Exception {
        ConnectBroker connectBrocker = new ConnectBroker(Environment.getDefault());
        ((StandaloneCallbackHolder) NuxeoConnectClient.getCallBackHolder()).setTestMode(true);
        connectBrocker.setAllowSNAPSHOT(false);
        connectBrocker.setRelax("true");

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.2.0", "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1",
                        "B-1.0.2", "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        assertThat(connectBrocker.pkgUpgrade()).isTrue();

        // After: [studioA-1.0.2-SNAPSHOT, hfA-1.0.8, A-1.2.2, B-1.0.2, C-1.0.0, D-1.0.4-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "A-1.2.2", "B-1.0.2",
                "C-1.0.0", "D-1.0.4-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.0", "studioA-1.0.1", "hfA-1.0.0", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.0.0", "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.0", "A-1.2.3-SNAPSHOT",
                        "B-1.0.1-SNAPSHOT", "B-1.0.1", "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.2-SNAPSHOT",
                        "D-1.0.3-SNAPSHOT"),
                PackageState.DOWNLOADED);
    }

    @Test
    public void testUpgradePackageRequestWithRelaxAndSnapshot() throws Exception {
        ConnectBroker connectBrocker = new ConnectBroker(Environment.getDefault());
        ((StandaloneCallbackHolder) NuxeoConnectClient.getCallBackHolder()).setTestMode(true);
        connectBrocker.setAllowSNAPSHOT(true);
        connectBrocker.setRelax("true");

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.2.0", "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1",
                        "B-1.0.2", "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        assertThat(connectBrocker.pkgUpgrade()).isTrue();

        // After: [studioA-1.0.2-SNAPSHOT, hfA-1.0.8, A-1.2.3-SNAPSHOT, B-1.0.2, C-1.0.2-SNAPSHOT, D-1.0.4-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "A-1.2.3-SNAPSHOT",
                "B-1.0.2", "C-1.0.2-SNAPSHOT", "D-1.0.4-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.0", "studioA-1.0.1", "hfA-1.0.0", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.0.0", "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.0", "B-1.0.1-SNAPSHOT",
                        "B-1.0.1", "C-1.0.1-SNAPSHOT", "C-1.0.0", "D-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT"),
                PackageState.DOWNLOADED);
    }

    @Test
    public void testUpgradePackageRequestWithTargetPlatform() throws Exception {
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_NAME, "server");
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_VERSION, "8.3");
        ConnectBroker connectBrocker = new ConnectBroker(Environment.getDefault());
        ((StandaloneCallbackHolder) NuxeoConnectClient.getCallBackHolder()).setTestMode(true);
        connectBrocker.setAllowSNAPSHOT(false);

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.2.0", "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1",
                        "B-1.0.2", "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        assertThat(connectBrocker.pkgUpgrade()).isTrue();

        // After: [studioA-1.0.2-SNAPSHOT, hfA-1.0.8, A-1.2.0, B-1.0.1, C-1.0.0, D-1.0.3-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "A-1.2.0", "B-1.0.1",
                "C-1.0.0", "D-1.0.3-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.0", "studioA-1.0.1", "hfA-1.0.0", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.0.0", "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT",
                        "B-1.0.1-SNAPSHOT", "B-1.0.2", "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.2-SNAPSHOT",
                        "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);
    }

    @Test
    public void testUpgradePackageRequestWithTargetPlatformAndSnapshot() throws Exception {
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_NAME, "server");
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_VERSION, "8.3");
        ConnectBroker connectBrocker = new ConnectBroker(Environment.getDefault());
        ((StandaloneCallbackHolder) NuxeoConnectClient.getCallBackHolder()).setTestMode(true);
        connectBrocker.setAllowSNAPSHOT(true);

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.2.0", "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1",
                        "B-1.0.2", "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        assertThat(connectBrocker.pkgUpgrade()).isTrue();

        // After: [studioA-1.0.2-SNAPSHOT, hfA-1.0.8, A-1.2.1-SNAPSHOT, B-1.0.1, C-1.0.1-SNAPSHOT, D-1.0.3-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "A-1.2.1-SNAPSHOT",
                "B-1.0.1", "C-1.0.1-SNAPSHOT", "D-1.0.3-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.0", "studioA-1.0.1", "hfA-1.0.0", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.0.0", "A-1.2.0", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1-SNAPSHOT",
                        "B-1.0.2", "C-1.0.0", "C-1.0.2-SNAPSHOT", "D-1.0.2-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);
    }

    @Test
    public void testHotfixPackageRequest() throws Exception {
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_NAME, "server");
        Environment.getDefault().setProperty(Environment.DISTRIBUTION_VERSION, "8.3");
        ConnectBroker connectBrocker = new ConnectBroker(Environment.getDefault());
        ((StandaloneCallbackHolder) NuxeoConnectClient.getCallBackHolder()).setTestMode(true);
        connectBrocker.setAllowSNAPSHOT(false);

        // Before: [studioA-1.0.0, hfA-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "A-1.0.0", "B-1.0.1-SNAPSHOT",
                "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "hfB-1.0.0", "hfC-1.0.0-SNAPSHOT",
                        "A-1.2.0", "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1",
                        "B-1.0.2", "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        assertThat(connectBrocker.pkgHotfix()).isTrue();

        // After: [studioA-1.0.0, hfA-1.0.0, hfB-1.0.0, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0, D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "hfB-1.0.0", "A-1.0.0",
                "B-1.0.1-SNAPSHOT", "C-1.0.0", "D-1.0.2-SNAPSHOT"), PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "hfC-1.0.0-SNAPSHOT", "A-1.2.0",
                        "A-1.2.1-SNAPSHOT", "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1", "B-1.0.2",
                        "C-1.0.1-SNAPSHOT", "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);

        connectBrocker.setAllowSNAPSHOT(true);
        assertThat(connectBrocker.pkgHotfix()).isTrue();

        // After: [studioA-1.0.0, hfA-1.0.0, hfB-1.0.0, hfC-1.0.0-SNAPSHOT, A-1.0.0, B-1.0.1-SNAPSHOT, C-1.0.0,
        // D-1.0.2-SNAPSHOT]
        checkPackagesState(connectBrocker, Arrays.asList("studioA-1.0.0", "hfA-1.0.0", "hfB-1.0.0",
                "hfC-1.0.0-SNAPSHOT", "A-1.0.0", "B-1.0.1-SNAPSHOT", "C-1.0.0", "D-1.0.2-SNAPSHOT"),
                PackageState.STARTED);
        checkPackagesState(connectBrocker,
                Arrays.asList("studioA-1.0.1", "studioA-1.0.2-SNAPSHOT", "hfA-1.0.8", "A-1.2.0", "A-1.2.1-SNAPSHOT",
                        "A-1.2.2-SNAPSHOT", "A-1.2.2", "A-1.2.3-SNAPSHOT", "B-1.0.1", "B-1.0.2", "C-1.0.1-SNAPSHOT",
                        "C-1.0.2-SNAPSHOT", "D-1.0.3-SNAPSHOT", "D-1.0.4-SNAPSHOT"),
                PackageState.DOWNLOADED);
    }

    private void checkPackagesState(ConnectBroker connectBrocker, List<String> packageIdList,
            PackageState expectedState) {
        Map<String, PackageState> states = connectBrocker.getUpdateService().getPersistence().getStates();
        for (String pkgId : packageIdList) {
            assertThat(states.get(pkgId)).isEqualTo(expectedState);
        }
    }

}
