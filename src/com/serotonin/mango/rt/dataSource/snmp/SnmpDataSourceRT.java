/*
    Mango - Open Source M2M - http://mango.serotoninsoftware.com
    Copyright (C) 2006-2011 Serotonin Software Technologies Inc.
    @author Matthew Lohbihler
    
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.serotonin.mango.rt.dataSource.snmp;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

import com.serotonin.mango.Common;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.scada_lts.ds.snmp.SNMPGet;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import com.serotonin.ShouldNeverHappenException;
import com.serotonin.mango.rt.dataImage.DataPointRT;
import com.serotonin.mango.rt.dataImage.PointValueTime;
import com.serotonin.mango.rt.dataImage.SetPointSource;
import com.serotonin.mango.rt.dataSource.DataSourceRT;
import com.serotonin.mango.rt.dataSource.PollingDataSource;
import com.serotonin.mango.vo.dataSource.snmp.SnmpDataSourceVO;
import com.serotonin.mango.vo.dataSource.snmp.SnmpPointLocatorVO;
import com.serotonin.web.i18n.LocalizableMessage;

/**
 * @author Matthew Lohbihler
 * 
 */
enum MessageType{
	oidError,
	unknownOid,
	undefined

}

class SnmpResponses {

	private final Log log = LogFactory.getLog(SnmpResponses.class);

	private PDU request = null;
	private PDU response = null;
	private long responseTime;

	public SnmpResponses(){}

	public void setRequest(PDU request){

		this.request = request;
	}
	public PDU getRequest(){

		return this.request;
	}
	public PDU getResponseByGet(Snmp snmp, Target target) {

		try {
			startTime();
			response = getResponse(true, snmp, target);
			finishTime();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return response;
	}
	public PDU getResponseBySet(Snmp snmp, Target target){
		PDU response = null;
		try {
			startTime();
			response = getResponse(false, snmp, target);
			finishTime();
		} catch (Exception ex) {
			// TODO add error handling
			response = null;
		}
		return response;
	}
	private PDU getResponse(boolean setOrGet, Snmp snmp, Target target) throws IOException {

		return setOrGet?snmp.get(request, target).getResponse():snmp.set(request, target).getResponse();

	}
	private void startTime(){

		responseTime = System.currentTimeMillis();

	}
	private void finishTime(){

		responseTime = System.currentTimeMillis() - responseTime;
		log.debug("Snmp request/response time: " + responseTime);

	}
}


public class SnmpDataSourceRT extends PollingDataSource {

	private static final Log log = LogFactory.getLog(SnmpDataSourceRT.class);

	public static final int DATA_SOURCE_EXCEPTION_EVENT = 1;
	public static final int PDU_EXCEPTION_EVENT = 2;

	private final SnmpDataSourceVO vo;
	private final Version version;
	private String address;
	private Target target;
	private Snmp snmp;
	private int counterEmptyResponsesOrResponsesWithError;
	private boolean deviceDidNotRespondDespiteTheCounterOfRetries = Boolean.FALSE;
	private SnmpResponses snmpRequests;

	public SnmpDataSourceRT(SnmpDataSourceVO vo) {
		super(vo);
		setPollingPeriod(vo.getUpdatePeriodType(), vo.getUpdatePeriods(), false);
		this.vo = vo;
		version = Version.getVersion(vo.getSnmpVersion(), vo.getCommunity(),
				vo.getSecurityName(), vo.getAuthProtocol(),
				vo.getAuthPassphrase(), vo.getPrivProtocol(),
				vo.getPrivPassphrase(), vo.getEngineId(),
				vo.getContextEngineId(), vo.getContextName());
		snmpRequests = new SnmpResponses();
	}

	public Snmp getSnmp() {
		return snmp;
	}

	@Override
	public void setPointValue(DataPointRT dataPoint, PointValueTime valueTime,
			SetPointSource source) {
		PDU request = version.createPDU();
		SnmpPointLocatorRT locator = dataPoint.getPointLocator();
		request.add(new VariableBinding(getOid(dataPoint), locator
				.valueToVariable(valueTime.getValue())));
		snmpRequests.setRequest(request);
		PDU response = snmpRequests.getResponseBySet(snmp, target);

		LocalizableMessage message = validatePdu(response);
		if (message != null)
			raiseEvent(PDU_EXCEPTION_EVENT, valueTime.getTime(), false, message);
		else
			dataPoint.setPointValue(valueTime, source);
	}

	public void setDeviceDidNotRespondDespiteTheCounterOfRetries(boolean deviceDidNotRespondDespiteTheCounterOfRetries) {
		this.deviceDidNotRespondDespiteTheCounterOfRetries = deviceDidNotRespondDespiteTheCounterOfRetries;
		log.info("Device did not respond despite the counter of retries.");
	}

	public void createSnmpAndStartListening(){
		try {
			initializeComponents();
		} catch (Exception e) {
			log.info(e.getMessage());
		}
	}

	public boolean isSnmpConnectionIsAlive(){
		if (target.getRetries() == counterEmptyResponsesOrResponsesWithError) {
			setDeviceDidNotRespondDespiteTheCounterOfRetries(Boolean.TRUE);
			return Boolean.FALSE;
		}
		else
			return Boolean.TRUE;
	}

	@Override
	public void terminate() {
		super.terminate();

		SnmpTrapRouter.removeDataSource(this);

		try {
			if (snmp != null)
				snmp.close();
		} catch (IOException e) {
			throw new ShouldNeverHappenException(e);
		}
	}

	//
	// /
	// / Lifecycle
	// /
	//
	@Override
	public void initialize() {
//		try {
//			initializeComponents();
//			counterEmptyResponsesOrResponsesWithError=0;
//			log.info("Counter Empty Responses Or Responses With Error is set 0.");
//
//			SnmpTrapRouter.addDataSource(this);
//
//			// Deactivate any existing event.
//			returnToNormal(DATA_SOURCE_EXCEPTION_EVENT,
//					System.currentTimeMillis());
//		} catch (Exception e) {
//			raiseEvent(DATA_SOURCE_EXCEPTION_EVENT, System.currentTimeMillis(),
//					true, DataSourceRT.getExceptionMessage(e));
//			log.debug("Error while initializing data source", e);
//			return;
//		}

		super.initialize();
	}

//	int getTrapPort() {
//		return vo.getTrapPort();
//	}
//
//	String getLocalAddress() {
//		return vo.getLocalAddress();
//	}
//
//	String getAddress() {
//		return address;
//	}
//
//	Version getVersion() { return this.version; }

	void receivedTrap(PDU trap) {
		long time = System.currentTimeMillis();
		VariableBinding vb;

		// Take a look at the response.
		LocalizableMessage message = validatePdu(trap);
		if (message != null)
			raiseEvent(PDU_EXCEPTION_EVENT, time, false, message);
		else {
			synchronized (pointListChangeLock) {
				updateChangedPoints();

				for (int i = 0; i < trap.getVariableBindings().size(); i++) {
					vb = trap.get(i);
					boolean found = false;

					// Find the command for this binding.
					for (DataPointRT dp : dataPoints) {
						if (getOid(dp).equals(vb.getOid())) {
							updatePoint(dp, vb.getVariable(), time);
							found = true;
						}
					}

					if (!found)
//						raiseEvent(TRAP_NOT_HANDLED_EVENT, time, false, new LocalizableMessage("event.snmp.trapNotHandled", vb));
						log.warn("Trap not handled: " + vb);
				}
			}
		}
	}


	@Override
	protected void doPoll(long time) {
		try {
			doPollImpl(time);
		} catch (Exception e) {
			log.error(e);
			raiseEvent(PDU_EXCEPTION_EVENT, time, true,
					DataSourceRT.getExceptionMessage(e));
		}
	}

	private void doPollImpl(long time) throws IOException {

		// no trap //if (!getLocatorVO(dp).isTrapOnly())
		if (vo.getSnmpVersion()==1) {
			VariableBinding[] oidsValue = new VariableBinding[dataPoints.size()];
			for (int i=0;i<dataPoints.size();i++) {
				DataPointRT dpRT = dataPoints.get(i);
				OID oid = getOid(dpRT);
				oidsValue[i] = new VariableBinding(oid);
			}
			PDU response = new SNMPGet().get(vo.getHost(),vo.getPort(),vo.getRetries(), vo.getTimeout(), vo.getCommunity(), oidsValue );

			if (
					response != null &&
					response.getErrorStatus() == PDU.noError
			) {
				for (int i=0; i<dataPoints.size();i++) {

					DataPointRT dp = dataPoints.get(i);
					VariableBinding vb = response.get(i);
					// index the same in dataPoints and array VariableBinding (check test)
					if (!getOid(dp).equals(vb.getOid())) {
						new RuntimeException("not the same index in array VariableBinding and dataPoints");
					} else {
						updatePoint(dp, vb.getVariable(), time);
					}
				}
			} else {
				raiseEvent(PDU_EXCEPTION_EVENT, time, true, validatePdu(response));
			}

		}

	}

	private LocalizableMessage validatePdu(PDU pdu) {
		if (pdu == null)
			return new LocalizableMessage("event.snmp.noResponse");

		if (pdu.getErrorIndex() != 0)
			return new LocalizableMessage("event.snmp.pduOidError", pdu.get(
					pdu.getErrorIndex() - 1).getOid(), pdu.getErrorStatusText());

		if (pdu.getErrorStatus() != 0)
			return new LocalizableMessage("event.snmp.pduErrorStatus",
					pdu.getErrorStatus(), pdu.getErrorStatusText());
		return null;
	}

	private void increaseCounterIfErrorExistOrNoResponseAppear(PDU pdu) {
		if ((pdu == null) || (pdu.getErrorIndex() != 0) || (pdu.getErrorStatus() != 0)) {
			++counterEmptyResponsesOrResponsesWithError;
			log.info("Counter Empty Responses Or Responses With Error: "+counterEmptyResponsesOrResponsesWithError);
		}
	}

	private OID getOid(DataPointRT dp) {
		return ((SnmpPointLocatorRT) dp.getPointLocator()).getOid();
	}

	private SnmpPointLocatorVO getLocatorVO(DataPointRT dp) {
		return ((SnmpPointLocatorRT) dp.getPointLocator()).getVO();
	}

	private void updatePoint(DataPointRT dp, Variable variable, long time) {
		SnmpPointLocatorRT locator = dp.getPointLocator();
		dp.updatePointValue(new PointValueTime(locator
				.variableToValue(variable), time));
	}

	private void initializeComponents() throws IOException {

		address = InetAddress.getByName(vo.getHost()).getHostAddress();
		target = version.getTarget(vo.getHost(), vo.getPort(),
				vo.getRetries(), vo.getTimeout());
		snmp = new Snmp(new DefaultUdpTransportMapping());
		snmp.listen();

	}

}
