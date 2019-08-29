package org.scada_lts.ds.snmp;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.net.InetAddress;

/**
 * @autor grzegorz.bylica@gmail.com on 26.08.2019
 */
public class SNMPGet {

    private static final Log log = LogFactory.getLog(SNMPGet.class);

    public PDU get(String host, int port, int retries, int timeout , String readCommunity, VariableBinding[] oids  ) {
        Snmp snmp = null;
        try{

            PDU pdu = new PDU();
            pdu.addAll(oids);
            pdu.setType(PDU.GETNEXT); // pdu.setType(PDU.GET) ?

            CommunityTarget target = new CommunityTarget();
            target.setCommunity(new OctetString(readCommunity));
            InetAddress inetAddress = InetAddress.getByName(host);
            Address address = new UdpAddress(inetAddress, port);
            target.setAddress(address);
            target.setRetries(retries);
            target.setTimeout(timeout);
            target.setVersion(SnmpConstants.version1);

            snmp = new Snmp(new DefaultUdpTransportMapping());

            snmp.listen();

            ResponseEvent response = snmp.get(pdu, target);

            if (response.getResponse() == null) {
                throw new RuntimeException("timeout snmp:"+snmp.toString());
            } else {
                log.trace("Received response from: "+
                        response.getPeerAddress());
                //response PDU
                return response.getResponse();
            }

        } catch (Exception e) {
            log.error(e);
        } finally {
           if (snmp != null) {
               try {
                   snmp.close();
               } catch (IOException e) {
                   log.error(e);
               }
           }
        }
        return null;
    }
}
