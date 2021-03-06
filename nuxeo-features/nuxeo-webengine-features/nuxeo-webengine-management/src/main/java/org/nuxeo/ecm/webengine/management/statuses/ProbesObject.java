/*
 * (C) Copyright 2010 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     mcedica
 */
package org.nuxeo.ecm.webengine.management.statuses;

import java.util.Collection;
import java.util.Iterator;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import org.nuxeo.ecm.core.management.api.ProbeInfo;
import org.nuxeo.ecm.core.management.api.ProbeManager;
import org.nuxeo.ecm.webengine.management.ManagementObject;
import org.nuxeo.ecm.webengine.model.WebObject;
import org.nuxeo.ecm.webengine.model.exceptions.WebResourceNotFoundException;
import org.nuxeo.ecm.webengine.model.impl.DefaultObject;
import org.nuxeo.runtime.api.Framework;

/**
 * List and run probes
 *
 * @author matic
 */
@WebObject(type = "Probes")
@Produces("text/html; charset=UTF-8")
public class ProbesObject extends ManagementObject {

    protected ProbeManager probeMgr;

    protected Collection<ProbeInfo> infos;

    public static ProbesObject newProbes(DefaultObject parent) {
        return (ProbesObject) parent.newObject("Probes");
    }

    @Override
    protected void initialize(Object... args) {
        assert args != null && args.length == 2;
        probeMgr = Framework.getLocalService(ProbeManager.class);
        infos = probeMgr.getAllProbeInfos();
    }

    @GET
    public Object doGet() {
        return getView("index");
    }

    public Collection<ProbeInfo> getInfos() {
        return infos;
    }

    @GET
    @Path("availability")
    @Produces("text/plain")
    public Object doGetAvailability() {
        probeMgr.runAllProbes();
        return getView("availability").arg("isAvailable", probeMgr.getProbesInError().isEmpty());
    }

    @Path("{probe}")
    public ProbeObject doGetProbe(@PathParam("probe") String name) {
        Iterator<ProbeInfo> it = infos.iterator();
        while (it.hasNext()) {
            ProbeInfo info = it.next();
            if (info.getShortcutName().equals(name)) {
                return ProbeObject.newProbe(this, info);
            }
        }
        throw new WebResourceNotFoundException("No such probe " + name);
    }

    @PUT
    public Object doPut() {
        return doRun();
    }

    @GET
    @Path("/@run")
    public Object doRun() {
        probeMgr.runAllProbes();
        return redirect(getPath());
    }
}
