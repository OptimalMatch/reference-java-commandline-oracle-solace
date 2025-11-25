package com.example.solace;

import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPException;

public class SolaceConnection {

    public static JCSMPSession createSession(ConnectionOptions options) throws JCSMPException {
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, options.host);
        properties.setProperty(JCSMPProperties.VPN_NAME, options.vpn);
        properties.setProperty(JCSMPProperties.USERNAME, options.username);
        properties.setProperty(JCSMPProperties.PASSWORD, options.password);
        
        // Additional recommended settings
        properties.setProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
        properties.setProperty(JCSMPProperties.CLIENT_NAME, 
            "solace-cli-" + System.currentTimeMillis());
        
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();
        
        return session;
    }
}
