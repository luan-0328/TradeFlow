package com.tradeflow.web;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
class ManagementEndpointPortFilterTest {
    /** 关闭观测采样和动态管理端口不能让业务端口管理路径落入不存在资源的500处理。 */
    @Test void actualManagementPortIsRequiredEvenWhenMetricsDisabled()throws Exception{
        var env=new MockEnvironment().withProperty("management.server.port","0").withProperty("local.management.port","18081").withProperty("tradeflow.observability.enabled","false");
        var filter=new ManagementEndpointPortFilter(env);
        var request=new MockHttpServletRequest("GET","/actuator/health");request.setLocalPort(8081);
        var denied=new MockHttpServletResponse();var chain=new MockFilterChain();filter.doFilter(request,denied,chain);
        assertThat(denied.getStatus()).isEqualTo(403);assertThat(chain.getRequest()).isNull();
        request=new MockHttpServletRequest("GET","/actuator/health/readiness");request.setLocalPort(18081);
        var allowed=new MockFilterChain();filter.doFilter(request,new MockHttpServletResponse(),allowed);assertThat(allowed.getRequest()).isNotNull();
    }
    @Test void ordinaryBusinessAndDefaultSamePortRemainAvailable()throws Exception{
        var filter=new ManagementEndpointPortFilter(new MockEnvironment());
        var chain=new MockFilterChain();filter.doFilter(new MockHttpServletRequest("GET","/actuator/health"),new MockHttpServletResponse(),chain);assertThat(chain.getRequest()).isNotNull();
        chain=new MockFilterChain();filter.doFilter(new MockHttpServletRequest("GET","/api/v1/orders"),new MockHttpServletResponse(),chain);assertThat(chain.getRequest()).isNotNull();
    }
}
