package com.tradeflow.payment;

import com.tradeflow.payment.api.PlatformActionAdvice;
import com.tradeflow.common.ApiResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

/** 仅验证平台投影入口；不启动服务，不把候选补投动作等同资金guard或Broker验收。 */
class PlatformMessageActionAdviceTest {
    private final PlatformActionAdvice advice=new PlatformActionAdvice();
    private final String base="/api/v1/admin/payment-messages/";

    @Test void frozenSentOutboxOffersExplicitOriginalRedelivery() {
        for(Object frozen:List.of(true,1,1L))
            assertThat(actions("outbox",Map.of("status","SENT","wire_frozen",frozen))).isEqualTo(List.of("REDELIVER_ORIGINAL"));
    }
    @Test void sentWithoutFrozenOriginalDoesNotOfferRedelivery() {
        assertThat(actions("outbox",Map.of("status","SENT"))).isEmpty();
        for(Object frozen:List.of(false,0,0L))
            assertThat(actions("outbox",Map.of("status","SENT","wire_frozen",frozen))).isEmpty();
    }
    @Test void sentFailureAndDeferredAreNotOutboxReplayCandidates() {
        for(String kind:List.of("failure","deferred"))
            assertThat(actions(kind,Map.of("status","SENT","wire_frozen",true))).isEmpty();
    }
    @Test void runningOrNotSentOutboxDoesNotOfferOriginalRedelivery() {
        for(String state:List.of("RUNNING","READY","APPLIED"))
            assertThat(actions("outbox",Map.of("status",state,"wire_frozen",true))).isEmpty();
        assertThat(actions("outbox",Map.of("status","MANUAL_REVIEW","wire_frozen",true))).isEqualTo(List.of("RETRY"));
    }
    @Test void projectionPreservesSnakeCaseMetadataAndNeverIncludesWireBody() {
        Map<String,Object> original=Map.of("event_id","evt_original","status","SENT","wire_frozen",true);
        ApiResponse<?> response=project("GET",base+"outbox",original);
        Map<?,?> row=(Map<?,?>)((List<?>)((Map<?,?>)response.data()).get("items")).getFirst();
        assertThat(row.get("event_id")).isEqualTo("evt_original");
        assertThat(row.get("allowedActions")).isEqualTo(List.of("REDELIVER_ORIGINAL"));
        assertThat(row.containsKey("wire_payload")).isFalse();
        assertThat(original.containsKey("allowedActions")).isFalse();
        assertThat(response.requestId()).isEqualTo("req_fixture");
    }
    @Test void adviceDoesNotAnnotateWritesOrBuyerResponses() {
        var original=ApiResponse.success(Map.of("status","SENT","wire_frozen",true),"req_fixture","trace_fixture");
        for(String method:List.of("POST","PATCH"))assertThat(invoke(method,base+"outbox",original)).isSameAs(original);
        assertThat(invoke("GET","/api/v1/orders",original)).isSameAs(original);
    }
    private List<?> actions(String kind,Map<String,Object> row) {
        ApiResponse<?> response=project("GET",base+kind,row);
        Map<?,?> first=(Map<?,?>)((List<?>)((Map<?,?>)response.data()).get("items")).getFirst();
        return (List<?>)first.get("allowedActions");
    }
    private ApiResponse<?> project(String method,String path,Map<String,Object> row) {
        return (ApiResponse<?>)invoke(method,path,ApiResponse.success(Map.of("items",List.of(row)),"req_fixture","trace_fixture"));
    }
    private Object invoke(String method,String path,Object body) {
        return advice.beforeBodyWrite(body,null,MediaType.APPLICATION_JSON,null,
                new ServletServerHttpRequest(new MockHttpServletRequest(method,path)),
                new ServletServerHttpResponse(new MockHttpServletResponse()));
    }
}
