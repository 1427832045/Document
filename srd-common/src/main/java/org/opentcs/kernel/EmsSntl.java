package org.opentcs.kernel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sntl.licensing.*;

public class EmsSntl {
    private static final Logger LOG = LoggerFactory.getLogger(EmsSntl.class);

    public boolean checkAuthority() {
        try {
            String featureName = "rr_main";
            String contactServer = "no-net";
            String scopeFeatureInfo = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + "<sentinelScope>"
                    + "<feature index=\"0\">" + "<name>" + featureName + "</name>" + "<version>" + "</version>"
                    + "</feature>" + "</sentinelScope>";
            Attribute attrAppContext = new Attribute();
            attrAppContext.set(LicensingConstants.SNTL_ATTR_APPCONTEXT_CONTACT_SERVER, contactServer);
            attrAppContext.set(LicensingConstants.SNTL_ATTR_APPCONTEXT_CONTROL_REMOTE_SESSION,
                    LicensingConstants.SNTL_CONTROL_REMOTE_NONE);
            attrAppContext.set(LicensingConstants.SNTL_ATTR_APPCONTEXT_AUTO_REFRESH, LicensingConstants.SNTL_ATTR_NO);
            attrAppContext.set(LicensingConstants.SNTL_ATTR_APPCONTEXT_ENABLE_LOCAL_RENEWAL, LicensingConstants.SNTL_ATTR_NO);
            ApplicationContext appContext = new ApplicationContext(attrAppContext);
            Attribute attrLogin = new Attribute();
            LoginSession session = new LoginSession();
            session.login(appContext, featureName, attrLogin);
            String sessioninfo = session.getSessionInfo(LicensingConstants.SNTL_SESSIONINFO);
            session.refresh();
            session.logout();
            attrAppContext.dispose();
            attrLogin.dispose();
            appContext.dispose();
            LOG.info("Check roboroute authority successful.");
            LOG.info("sessioninfo:" + sessioninfo);
            LOG.info("scopeFeatureInfo:" + scopeFeatureInfo);
            return true;
        } catch (java.io.UnsupportedEncodingException e) {
            LOG.info(e.getMessage());
        } catch (LicensingException e) {
            LOG.info("It is Timeout error");
            LOG.info("Check roboroute authority failed with status " + e.getStatusCode());
            LOG.info("Check roboroute authority status Message : \n" + e.getMessage());
            return false;
        } finally {
            LicensingAPI.cleanup();
        }
        return false;
    }
}



