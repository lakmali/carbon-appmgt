/*
*  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.appmgt.usage.publisher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URL;
import javax.cache.Caching;

import org.apache.axis2.Constants;
import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.AbstractHandler;
import org.apache.synapse.rest.RESTConstants;
import org.wso2.carbon.appmgt.api.AppManagementException;
import org.wso2.carbon.appmgt.api.model.APIIdentifier;
import org.wso2.carbon.appmgt.api.model.WebApp;
import org.wso2.carbon.appmgt.impl.AppMConstants;
import org.wso2.carbon.appmgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.appmgt.usage.publisher.dto.RequestPublisherDTO;
import org.wso2.carbon.appmgt.usage.publisher.internal.APPManagerConfigurationServiceComponent;
import org.wso2.carbon.appmgt.usage.publisher.internal.UsageComponent;
import org.wso2.carbon.usage.agent.beans.APIManagerRequestStats;
import org.wso2.carbon.usage.agent.util.PublisherUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

public class APIMgtUsageHandler extends AbstractHandler {

    private static final Log log   = LogFactory.getLog(APIMgtUsageHandler.class);

    private volatile APIMgtUsageDataPublisher publisher;

    private boolean enabled = APPManagerConfigurationServiceComponent.getApiMgtConfigReaderService().isEnabled();

    private String publisherClass = APPManagerConfigurationServiceComponent.getApiMgtConfigReaderService().getPublisherClass();

    public boolean handleRequest(MessageContext mc) {

        try{
            long currentTime = System.currentTimeMillis();

            if (!enabled) {
                return true;
            }

            if (publisher == null) {
                synchronized (this){
                    if (publisher == null) {
                        try {
                            log.debug("Instantiating Data Publisher");
                            publisher = (APIMgtUsageDataPublisher)Class.forName(publisherClass).newInstance();
                            publisher.init();
                        } catch (ClassNotFoundException e) {
                            log.error("Class not found " + publisherClass);
                        } catch (InstantiationException e) {
                            log.error("Error instantiating " + publisherClass);
                        } catch (IllegalAccessException e) {
                            log.error("Illegal access to " + publisherClass);
                        }
                    }
                }
            }

            org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext) mc).
                    getAxis2MessageContext();

            Map<String, String> headers = (Map<String, String>) axis2MC.getProperty(
                    org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);

            String cookieString = headers.get(HTTPConstants.COOKIE_STRING);
            String saml2CookieValue = getCookieValue(cookieString, AppMConstants.APPM_SAML2_COOKIE);
            String loggedUser = (String) Caching.getCacheManager(AppMConstants.API_MANAGER_CACHE_MANAGER).getCache(AppMConstants.KEY_CACHE_NAME).get(saml2CookieValue);

            String referer = headers.get("Referer");
            URL appURL = new URL(referer);
            String page= appURL.getPath();
            String tracking_code = headers.get("trackingCode");
            String[] tracking_code_list;
            tracking_code_list = tracking_code.split(",");

            String username = "";
            String applicationName = "DefaultApplication";
            String applicationId = "1";

            username = loggedUser;

            String hostName = DataPublisherUtil.getHostAddress();
            String context = "/"+getContextWithVersion(referer)[0];
            String fullRequestPath = (String)mc.getProperty(RESTConstants.REST_FULL_REQUEST_PATH);
            int tenantDomainIndex = fullRequestPath.indexOf("/t/");
            String apiPublisher = MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;

            if (tenantDomainIndex != -1) {
                String temp = fullRequestPath.substring(tenantDomainIndex + 3, fullRequestPath.length());
                apiPublisher = temp.substring(0, temp.indexOf("/"));
            }


            String version = getContextWithVersion(referer)[1];
            WebApp webApp = getWebApp(context,version);
            String api = webApp.getId().getApiName();
            String api_version =   api + ":" + version;

            String hashcode = webApp.getTrackingCode();
            boolean trackingCodeExist = Arrays.asList(tracking_code_list).contains(hashcode);

            String resource = extractResource(mc);
            String method =  (String)((Axis2MessageContext) mc).getAxis2MessageContext().getProperty(
                    Constants.Configuration.HTTP_METHOD);
            String tenantDomain = MultitenantUtils.getTenantDomain(username);
            
            int tenantId = UsageComponent.getRealmService().getTenantManager().
                    getTenantId(tenantDomain);



            List<Long[]> timeList = UsageComponent.getResponseTime(page);

            long totalTime = 0L;
            long serviceTime = 0L;

            if(timeList != null){
                for (int x = 0; x < timeList.size(); x++) {
                    Long [] timaArray =   timeList.get(x);
                    totalTime = totalTime + timaArray[0];

                }

                serviceTime = totalTime/timeList.size();

            }

            UsageComponent.deleteResponseTime(page,System.currentTimeMillis());

            if (trackingCodeExist)  {

                RequestPublisherDTO requestPublisherDTO = new RequestPublisherDTO();
                requestPublisherDTO.setContext(context);
                requestPublisherDTO.setApi_version(api_version);
                requestPublisherDTO.setApi(api);
                requestPublisherDTO.setVersion(version);
                requestPublisherDTO.setResource(resource);
                requestPublisherDTO.setMethod(method);
                requestPublisherDTO.setRequestTime(currentTime);
                requestPublisherDTO.setUsername(username);
                requestPublisherDTO.setTenantDomain(tenantDomain);
                requestPublisherDTO.setHostName(hostName);
                requestPublisherDTO.setApiPublisher(apiPublisher);
                requestPublisherDTO.setApplicationName(applicationName);
                requestPublisherDTO.setApplicationId(applicationId);
                requestPublisherDTO.setTrackingCode(hashcode);
                requestPublisherDTO.setReferer(referer);
                requestPublisherDTO.setServiceTimeOfPage(serviceTime);

                publisher.publishEvent(requestPublisherDTO);
                //We check if usage metering is enabled for billing purpose
                if (DataPublisherUtil.isEnabledMetering()) {
                    //If usage metering enabled create new usage stat object and publish to bam
                    APIManagerRequestStats stats = new APIManagerRequestStats();
                    stats.setRequestCount(1);
                    stats.setTenantId(tenantId);
                    try {
                        //Publish stat to bam
                        PublisherUtils.publish(stats, tenantId);
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(e);
                        }
                        log.error("Error occurred while publishing request statistics. Full stacktrace available in debug logs. " + e.getMessage());
                    }
                }

                mc.setProperty(APIMgtUsagePublisherConstants.USER_ID, username);
                mc.setProperty(APIMgtUsagePublisherConstants.CONTEXT, context);
                mc.setProperty(APIMgtUsagePublisherConstants.APP_VERSION, api_version);
                mc.setProperty(APIMgtUsagePublisherConstants.API, api);
                mc.setProperty(APIMgtUsagePublisherConstants.VERSION, version);
                mc.setProperty(APIMgtUsagePublisherConstants.RESOURCE, resource);
                mc.setProperty(APIMgtUsagePublisherConstants.HTTP_METHOD, method);
                mc.setProperty(APIMgtUsagePublisherConstants.REQUEST_TIME, currentTime);
                mc.setProperty(APIMgtUsagePublisherConstants.HOST_NAME,hostName);
                mc.setProperty(APIMgtUsagePublisherConstants.API_PUBLISHER,apiPublisher);
                mc.setProperty(APIMgtUsagePublisherConstants.APPLICATION_NAME, applicationName);
                mc.setProperty(APIMgtUsagePublisherConstants.APPLICATION_ID, applicationId);
                mc.setProperty(APIMgtUsagePublisherConstants.TRACKING_CODE,hashcode);
                mc.setProperty(APIMgtUsagePublisherConstants.REFERER,referer);
                mc.setProperty(APIMgtUsagePublisherConstants.SERVICE_TIME_OF_PAGE,serviceTime);

        }

        }catch (Throwable e){
            log.error("Cannot publish event. " + e.getMessage(), e);
        }
        return true;
    }

    public boolean handleResponse(MessageContext mc) {

        return true; // Should never stop the message flow
    }

    private String extractResource(MessageContext mc){
        String resource = "/";
        Pattern pattern = Pattern.compile("^/.+?/.+?([/?].+)$");
        Matcher matcher = pattern.matcher((String) mc.getProperty(RESTConstants.REST_FULL_REQUEST_PATH));
        if (matcher.find()){
            resource = matcher.group(1);
        }
        return resource;
    }

    public String getCookieValue(String cookieString, String cookieName) {
        if (cookieString.length() > 0) {
            int cStart = cookieString.indexOf(cookieName + "=");
            int cEnd;
            if (cStart != -1) {
                cStart = cStart + cookieName.length() + 1;
                cEnd = cookieString.indexOf(";", cStart);
                if (cEnd == -1) {
                    cEnd = cookieString.length();
                }
                return cookieString.substring(cStart, cEnd);
            }
        }
        return "";
    }


    public String[] getContextWithVersion(String refer) {
        String webapp[]= new String[2];
        if (refer.length() > 0) {
           // e.g URL pattern : "http://localhost:8281/united-airline/1.0.0/";
            String s[]=refer.split("/");

            webapp[0] = s[3];
            webapp[1] = s[4];
             return webapp;
        }
        return webapp;
    }


    public WebApp getWebApp(String context,String version) throws AppManagementException {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        WebApp webApp =null;

        String sqlQuery = "SELECT APP_NAME,APP_PROVIDER,TRACKING_CODE FROM APM_APP WHERE CONTEXT=? AND APP_VERSION=?";


        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setString(1,context);
            ps.setString(2,version);
            rs = ps.executeQuery();
            while(rs.next()){
                String webAppname = rs.getString("APP_NAME");
                String provider = rs.getString("APP_PROVIDER");
                String trackingCode = rs.getString("TRACKING_CODE");
                APIIdentifier apiIdentifier = new APIIdentifier(provider,webAppname,version);
                webApp = new WebApp(apiIdentifier);
                webApp.setTrackingCode(trackingCode);
                webApp.setContext(context);

            }

        } catch (SQLException e) {
            log.error("Error when executing the SQL query to read the access key for user :", e);
            throw new AppManagementException("Error when executing the SQL query to read the access key for user :", e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
          return webApp;
    }



   }
