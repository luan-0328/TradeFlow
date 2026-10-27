package com.tradeflow.user;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;

/** 完整应用和真实所属MySQL库的启动/HTTP边界；不证明Nacos、MQ或七服务端到端。
 * 仅隔离fixture显式启用，普通单元测试阶段明确跳过；不模拟业务Bean或放宽生产安全。
 */
@EnabledIfEnvironmentVariable(named="TRADEFLOW_FULL_APPLICATION_FIXTURE",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class UserApplicationTest {
    private static com.icegreen.greenmail.util.GreenMail smtp;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired org.springframework.mail.javamail.JavaMailSenderImpl mailSender;
    @Autowired com.tradeflow.user.application.UserWorkspaceService workspace;
    @LocalServerPort int businessPort;
    @LocalManagementPort int managementPort;

    /** 写请求与429都必须保留首次响应；测试HTTP客户端禁止按Retry-After等待后自动重发。 */
    @org.junit.jupiter.api.BeforeEach void disableAutomaticHttpRetries() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    }

    /** 端口、密钥和凭据均由独占fixture进程提供，禁止回退到开发或远程数据库。 */
    @DynamicPropertySource
    static void fixture(DynamicPropertyRegistry properties) {
        smtp=new com.icegreen.greenmail.util.GreenMail(new com.icegreen.greenmail.util.ServerSetup(0,"127.0.0.1","smtp"));
        smtp.setUser("fixture@example.test","fixture@example.test","fixture-smtp-password");
        smtp.start();
        properties.add("spring.mail.host",()->"127.0.0.1");
        properties.add("spring.mail.port",()->smtp.getSmtp().getPort());
        properties.add("spring.mail.username",()->"fixture@example.test");
        properties.add("spring.mail.password",()->"fixture-smtp-password");
        properties.add("spring.mail.properties.mail.smtp.auth",()->true);
        properties.add("spring.mail.properties.mail.smtp.ssl.enable",()->false);
        properties.add("spring.data.redis.host",()->"127.0.0.1");
        properties.add("spring.data.redis.port",()->required("TRADEFLOW_EMAIL_REDIS_PORT"));
        properties.add("spring.data.redis.password",()->"");
        String port=required("TRADEFLOW_UPGRADE_MYSQL_PORT");
        int number=Integer.parseInt(port);
        if(number<1024||number>65535)throw new IllegalArgumentException("隔离MySQL端口必须为1024至65535");
        properties.add("spring.autoconfigure.exclude",()->"org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration");
        properties.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+port+"/trade_user?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.add("spring.datasource.username",()->"root");
        properties.add("spring.datasource.password",()->"");
        properties.add("spring.datasource.hikari.maximum-pool-size",()->3);
        properties.add("spring.datasource.hikari.minimum-idle",()->1);
        properties.add("spring.datasource.hikari.transaction-isolation",()->"TRANSACTION_READ_COMMITTED");
        properties.add("spring.datasource.hikari.connection-init-sql",()->"SET time_zone = '+00:00'");
        // fixture已应用原始Flyway脚本；应用启动不使用迁移账号、不重复迁移。
        properties.add("spring.flyway.enabled",()->false);
        properties.add("spring.cloud.nacos.discovery.enabled",()->false);
        properties.add("spring.cloud.nacos.discovery.register-enabled",()->false);
        properties.add("spring.cloud.nacos.config.enabled",()->false);
        properties.add("spring.cloud.sentinel.enabled",()->false);
        properties.add("spring.cloud.service-registry.auto-registration.enabled",()->false);
        properties.add("spring.rabbitmq.dynamic",()->false);
        properties.add("spring.rabbitmq.username",()->"guest");
        properties.add("spring.rabbitmq.password",()->"guest");
        properties.add("spring.rabbitmq.listener.simple.auto-startup",()->false);
        properties.add("spring.rabbitmq.listener.direct.auto-startup",()->false);
        properties.add("tradeflow.messaging.enabled",()->false);
        properties.add("tradeflow.observability.enabled",()->false);
        properties.add("tradeflow.notifications.enabled",()->false);
        properties.add("tradeflow.bootstrap.enabled",()->false);
        properties.add("management.server.address",()->"127.0.0.1");
        properties.add("management.server.port",()->0);
        properties.add("management.health.rabbit.enabled",()->false);
        properties.add("management.health.redis.enabled",()->false);
        properties.add("management.endpoint.health.group.readiness.include",()->"readinessState,db");
        properties.add("tradeflow.security.issuer",()->"tradeflow");
        Map.ofEntries(
            Map.entry("tradeflow.security.public-key","TRADEFLOW_JWT_PUBLIC_KEY"),
            Map.entry("tradeflow.security.private-key","TRADEFLOW_JWT_PRIVATE_KEY"),
            Map.entry("tradeflow.monitoring-token","TRADEFLOW_MONITORING_TOKEN"),
            Map.entry("tradeflow.internal.product-token","TRADEFLOW_PRODUCT_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.order-token","TRADEFLOW_ORDER_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.payment-token","TRADEFLOW_PAYMENT_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.aftersale-token","TRADEFLOW_AFTERSALE_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.channel-token","TRADEFLOW_CHANNEL_SERVICE_TOKEN"),
            Map.entry("tradeflow.internal.settlement-token","TRADEFLOW_INTERNAL_SETTLEMENT_TOKEN"),
            Map.entry("tradeflow.callback-secret","TRADEFLOW_CALLBACK_SECRET"),
            Map.entry("tradeflow.simulator-admin-token","TRADEFLOW_SIMULATOR_ADMIN_TOKEN")
        ).forEach((property,environment)->properties.add(property,()->required(environment)));
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException("完整应用fixture缺少配置："+name);
        return value;
    }
    @org.junit.jupiter.api.AfterAll static void stopSmtp(){if(smtp!=null)smtp.stop();}
    /** 校验Boot绑定的真实SMTP连接；仅隔离收件箱，失败可定位配置而不输出外部认证材料。 */
    @Test void smtpConfigurationCanDeliverToLocalMailbox() {
        smtp.setUser("transport@example.test","transport","fixture-only");
        var message=new org.springframework.mail.SimpleMailMessage();
        message.setFrom("fixture@example.test");message.setTo("transport@example.test");
        message.setSubject("fixture transport");message.setText("fixture transport");
        mailSender.send(message);
    }

    /** 实际Spring应用、真实Mapper和本库连接均启动，公开身份必须来自当前服务配置。 */
    @Test void startsCompleteApplicationWithRealOwnDatabaseAndCurrentIdentity() {
        var response=http.getForEntity("/system/info",JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().at("/data/serviceName").asText()).isEqualTo("tradeflow-user");
        assertThat(response.getBody().at("/data/stage").asText()).isEqualTo("B8_OBSERVABILITY_UNTESTED");
    }
    /** 管理端口与业务端口分离；readiness真实检查所属数据库，不拿应用启动替代业务端到端验收。 */
    @Test void managementReadinessUsesIndependentPortAndBusinessPortDoesNotExposeActuator() {
        assertThat(managementPort).isPositive().isNotEqualTo(businessPort);
        assertThat(http.getForEntity("/actuator/health",String.class).getStatusCode().value()).isEqualTo(403);
        var response=new TestRestTemplate().getForEntity("http://127.0.0.1:"+managementPort+"/actuator/health/readiness",JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
    }

    /** 真实用户HTTP闭环与真实本库事务：入驻授权、默认地址竞争、通知和会话撤销；不经过Nacos/MQ或其他业务服务。 */
    @Test void realHttpMerchantAddressNotificationAndPasswordJourney() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,18);
        String buyer="fixture_b_"+suffix,platform="fixture_p_"+suffix,merchant="merchant_fx_"+suffix;
        String originalPassword="Fixture-Password1!",newPassword="Fixture-NewPassword2!";
        String userId=register(buyer,originalPassword),platformId=register(platform,originalPassword);
        // 只为本次唯一测试账号准备平台角色，生产用例仍走真实HTTP与最新角色校验，不模拟任何业务Bean。
        assertThat(jdbc.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM role WHERE role_code='PLATFORM_ADMIN'",platformId)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE user_account SET token_version=token_version+1,version=version+1 WHERE id=?",platformId)).isEqualTo(1);
        String admin=login(platform,originalPassword),oldBuyer=login(buyer,originalPassword);
        var application=ok(call(HttpMethod.POST,"/api/v1/merchant-applications",oldBuyer,Map.of("merchantId",merchant,"name","真实HTTP测试商家","description","fixture隔离经营资料")),201);
        String applicationId=application.path("id").asText();
        assertThat(ok(call(HttpMethod.POST,"/api/v1/admin/merchant-applications/"+applicationId+"/review",admin,Map.of("approved",true,"reason","隔离fixture审核通过","version",0)),200).path("status").asText()).isEqualTo("APPROVED");
        assertThat(call(HttpMethod.GET,"/api/v1/users/me/capabilities",oldBuyer,null).getStatusCode().value()).isEqualTo(401);
        String token=login(buyer,originalPassword),secondSession=login(buyer,originalPassword);
        var capabilities=ok(call(HttpMethod.GET,"/api/v1/users/me/capabilities",token,null),200);
        assertThat(capabilities.path("merchantRoles").path(merchant).asText()).isEqualTo("ADMIN");
        assertThat(capabilities.path("merchantIds").toString()).contains(merchant);
        assertThat(capabilities.path("allowedActions").toString()).contains("MERCHANT_PRODUCTS");
        assertThat(ok(merchantCall(HttpMethod.GET,"/api/v1/merchants/"+merchant,token,merchant,null),200).path("merchantId").asText()).isEqualTo(merchant);
        assertThat(merchantCall(HttpMethod.GET,"/api/v1/merchants/"+merchant,token,"merchant_foreign",null).getStatusCode().value()).isEqualTo(403);
        // 停用经营不删除历史成员，历史处置身份仍由最新auth-context保留；此处只验证用户服务HTTP边界。
        ok(call(HttpMethod.PATCH,"/api/v1/admin/merchants/"+merchant+"/status",admin,Map.of("status","DISABLED","reason","fixture验证历史成员保留","version",0)),200);
        assertThat(ok(merchantCall(HttpMethod.GET,"/api/v1/merchants/"+merchant,token,merchant,null),200).path("status").asText()).isEqualTo("DISABLED");
        assertThat(ok(call(HttpMethod.GET,"/api/v1/users/me/capabilities",token,null),200).path("merchantIds").toString()).contains(merchant);

        var first=ok(call(HttpMethod.POST,"/api/v1/users/me/addresses",token,address("地址甲",false,0)),201);
        var second=ok(call(HttpMethod.POST,"/api/v1/users/me/addresses",token,address("地址乙",false,0)),201);
        String firstId=first.path("id").asText(),secondId=second.path("id").asText();
        assertThat(call(HttpMethod.GET,"/api/v1/users/me/addresses/"+firstId,admin,null).getStatusCode().value()).isEqualTo(404);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<JsonNode>> a=pool.submit(()->{start.await();return call(HttpMethod.PUT,"/api/v1/users/me/addresses/"+firstId,token,address("地址甲",true,0));});
            Future<ResponseEntity<JsonNode>> b=pool.submit(()->{start.await();return call(HttpMethod.PUT,"/api/v1/users/me/addresses/"+secondId,token,address("地址乙",true,0));});
            start.countDown();ok(a.get(20,TimeUnit.SECONDS),200);ok(b.get(20,TimeUnit.SECONDS),200);
        }finally{pool.shutdownNow();}
        var list=ok(call(HttpMethod.GET,"/api/v1/users/me/addresses",token,null),200);
        assertThat(list.size()).isEqualTo(2);long defaults=0;for(var entry:list)if(entry.path("defaultAddress").asBoolean())defaults++;
        assertThat(defaults).isEqualTo(1);
        var latest=ok(call(HttpMethod.GET,"/api/v1/users/me/addresses/"+firstId,token,null),200);
        var updated=ok(call(HttpMethod.PUT,"/api/v1/users/me/addresses/"+firstId,token,address("已编辑地址",latest.path("defaultAddress").asBoolean(),latest.path("version").asLong())),200);
        assertThat(updated.path("address").asText()).isEqualTo("已编辑地址");
        assertThat(call(HttpMethod.PUT,"/api/v1/users/me/addresses/"+firstId,token,address("旧版不能覆盖",false,latest.path("version").asLong())).getStatusCode().value()).isEqualTo(409);
        ok(call(HttpMethod.DELETE,"/api/v1/users/me/addresses/"+firstId+"?version="+updated.path("version").asLong(),token,null),200);
        assertThat(call(HttpMethod.GET,"/api/v1/users/me/addresses/"+firstId,token,null).getStatusCode().value()).isEqualTo(404);

        String title="HTTP通知-"+suffix;HttpHeaders internal=new HttpHeaders();internal.set("X-Caller-Service","order");internal.set("X-Service-Token",required("TRADEFLOW_ORDER_SERVICE_TOKEN"));
        var notification=Map.of("businessKey","http-fixture:"+suffix,"title",title,"content","真实HTTP通知幂等验证");
        String notifyPath="/internal/v1/users/"+userId+"/notifications";
        ok(http.exchange(notifyPath,HttpMethod.POST,new HttpEntity<>(notification,internal),JsonNode.class),200);
        ok(http.exchange(notifyPath,HttpMethod.POST,new HttpEntity<>(notification,internal),JsonNode.class),200);
        var notices=ok(call(HttpMethod.GET,"/api/v1/users/me/notifications?page=1&pageSize=100",token,null),200).path("items");
        var matched=new ArrayList<JsonNode>();for(var entry:notices)if(title.equals(entry.path("title").asText()))matched.add(entry);
        assertThat(matched).hasSize(1);String notificationId=matched.get(0).path("id").asText();
        ok(call(HttpMethod.POST,"/api/v1/users/me/notifications/"+notificationId+"/read",token,null),200);
        ok(call(HttpMethod.POST,"/api/v1/users/me/notifications/"+notificationId+"/read",token,null),200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE id=? AND user_id=? AND read_at IS NOT NULL",Integer.class,notificationId,userId)).isEqualTo(1);

        ok(call(HttpMethod.POST,"/api/v1/users/me/password",token,Map.of("oldPassword",originalPassword,"newPassword",newPassword)),200);
        assertThat(call(HttpMethod.GET,"/api/v1/users/me",token,null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET,"/api/v1/users/me",secondSession,null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.POST,"/api/v1/auth/login",null,Map.of("username",buyer+"@example.test","password",originalPassword)).getStatusCode().value()).isEqualTo(401);
        String fresh=login(buyer,newPassword);
        assertThat(ok(call(HttpMethod.GET,"/api/v1/users/me",fresh,null),200).path("userId").asText()).isEqualTo(userId);
        assertThat(passwords.matches(newPassword,jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id=?",String.class,userId))).isTrue();
        ok(call(HttpMethod.POST,"/api/v1/users/me/sessions/revoke-all",fresh,null),200);
        assertThat(call(HttpMethod.GET,"/api/v1/users/me",fresh,null).getStatusCode().value()).isEqualTo(401);
    }

    /** 同一真实账号A店ADMIN、B店OPERATOR；全部授权/拒绝走真实HTTP和本库事务，旧全局商家角色不能越店提升。 */
    @Test void realHttpPerMerchantRolesAndLastAdministratorProtection() {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,16),password="Fixture-RolesPassword1!";
        String adminName="fx_role_p_"+suffix,ownerName="fx_role_o_"+suffix,targetName="fx_role_t_"+suffix;
        String adminId=register(adminName,password),ownerId=register(ownerName,password),targetId=register(targetName,password);
        String bAdminId=register("fx_role_b_"+suffix,password),a="merchant_role_a_"+suffix,b="merchant_role_b_"+suffix;
        // fixture只准备独立经营主体和平台授权；成员职级变更由真实受控HTTP入口完成。
        jdbc.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM role WHERE role_code='PLATFORM_ADMIN'",adminId);
        jdbc.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM role WHERE role_code='MERCHANT_ADMIN'",ownerId);
        jdbc.update("INSERT INTO merchant(merchant_id,name,description) VALUES(?,?,?)",a,"A店职级测试","隔离HTTP角色fixture");
        jdbc.update("INSERT INTO merchant(merchant_id,name,description) VALUES(?,?,?)",b,"B店职级测试","隔离HTTP角色fixture");
        String admin=login(adminName,password);
        ok(call(HttpMethod.POST,"/api/v1/admin/merchant-members",admin,member(ownerId,a,"ACTIVE","ADMIN")),200);
        ok(call(HttpMethod.POST,"/api/v1/admin/merchant-members",admin,member(ownerId,b,"ACTIVE","OPERATOR")),200);
        ok(call(HttpMethod.POST,"/api/v1/admin/merchant-members",admin,member(bAdminId,b,"ACTIVE","ADMIN")),200);
        String owner=login(ownerName,password),oldTarget=login(targetName,password);
        var capabilities=ok(call(HttpMethod.GET,"/api/v1/users/me/capabilities",owner,null),200);
        assertThat(capabilities.path("merchantRoles").path(a).asText()).isEqualTo("ADMIN");
        assertThat(capabilities.path("merchantRoles").path(b).asText()).isEqualTo("OPERATOR");
        assertThat(ok(merchantCall(HttpMethod.GET,"/api/v1/merchants/"+b,owner,b,null),200).path("merchantId").asText()).isEqualTo(b);
        assertThat(merchantCall(HttpMethod.GET,"/api/v1/merchants/"+b+"/members",owner,b,null).getStatusCode().value()).isEqualTo(403);
        // B店自提权和对他人授权都拒绝，A店ADMIN身份和旧全局ADMIN均不能替代B店职级。
        assertThat(merchantCall(HttpMethod.PUT,"/api/v1/merchants/"+b+"/members",owner,b,member(ownerId,b,"ACTIVE","ADMIN")).getStatusCode().value()).isEqualTo(403);
        assertThat(merchantCall(HttpMethod.PUT,"/api/v1/merchants/"+b+"/members",owner,b,member(targetId,b,"ACTIVE","OPERATOR")).getStatusCode().value()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT role FROM merchant_member WHERE user_id=? AND merchant_id=?",String.class,ownerId,b)).isEqualTo("OPERATOR");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_member WHERE user_id=? AND merchant_id=?",Integer.class,targetId,b)).isZero();
        var members=ok(merchantCall(HttpMethod.PUT,"/api/v1/merchants/"+a+"/members",owner,a,member(targetId,a,"ACTIVE","OPERATOR")),200);
        boolean found=false;for(var entry:members)if(targetId.equals(entry.path("userId").asText())){assertThat(entry.path("role").asText()).isEqualTo("OPERATOR");found=true;}
        assertThat(found).isTrue();
        assertThat(call(HttpMethod.GET,"/api/v1/users/me",oldTarget,null).getStatusCode().value()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id=? AND business_no=? AND action='MERCHANT_MEMBER' AND JSON_UNQUOTE(JSON_EXTRACT(change_summary,'$.userId'))=?",Integer.class,ownerId,a,targetId)).isEqualTo(1);
        long before=jdbc.queryForObject("SELECT token_version FROM user_account WHERE id=?",Long.class,ownerId);
        // 最后一个可登录A店ADMIN既不能降级、成员停用，也不能借全局账号停用绕过；失败不得撤销Token或写审计。
        long audits=jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id=?",Long.class,ownerId);
        var demote=merchantCall(HttpMethod.PUT,"/api/v1/merchants/"+a+"/members",owner,a,member(ownerId,a,"ACTIVE","OPERATOR"));
        assertLastMerchantAdmin(demote);
        assertLastMerchantAdmin(merchantCall(HttpMethod.PUT,"/api/v1/merchants/"+a+"/members",owner,a,member(ownerId,a,"DISABLED","ADMIN")));
        assertLastMerchantAdmin(call(HttpMethod.PATCH,"/api/v1/admin/users/"+ownerId,admin,Map.of("status","DISABLED","reason","验证最后店长保护")));
        assertThat(jdbc.queryForObject("SELECT token_version FROM user_account WHERE id=?",Long.class,ownerId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id=?",Long.class,ownerId)).isEqualTo(audits);
        // 合法恢复/交接路径：先为另一有效账号授A店ADMIN，再降级原ADMIN；A变更不改变B店职级。
        ok(merchantCall(HttpMethod.PUT,"/api/v1/merchants/"+a+"/members",owner,a,member(targetId,a,"ACTIVE","ADMIN")),200);
        ok(merchantCall(HttpMethod.PUT,"/api/v1/merchants/"+a+"/members",owner,a,member(ownerId,a,"ACTIVE","OPERATOR")),200);
        assertThat(call(HttpMethod.GET,"/api/v1/users/me",owner,null).getStatusCode().value()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT role FROM merchant_member WHERE user_id=? AND merchant_id=?",String.class,ownerId,b)).isEqualTo("OPERATOR");
    }
    private Map<String,Object> member(String user,String merchant,String status,String role) {return Map.of("userId",user,"merchantId",merchant,"status",status,"role",role,"reason","隔离HTTP职级验证");}
    /** HTTP真实调用注解Bean；新默认地址插入失败时，必须连同清除旧默认和版本递增一起回滚。 */
    @Test void annotatedAddressFailureRestoresOriginalDefault() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(workspace)).isTrue();
        String suffix=UUID.randomUUID().toString().replace("-","");
        String username="fixture_tx_"+suffix.substring(0,16),password="Fixture-Password1!";
        String user=register(username,password),token=login(username,password);
        var original=ok(call(HttpMethod.POST,"/api/v1/users/me/addresses",token,address("原默认地址",true,0)),201);
        String trigger="reject_address_"+suffix.substring(0,12);
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON user_address FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture address failure'");
        try {
            assertThat(call(HttpMethod.POST,"/api/v1/users/me/addresses",token,address("不应保存",true,0)).getStatusCode().value()).isEqualTo(500);
        } finally {jdbc.execute("DROP TRIGGER "+trigger);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE user_id=? AND deleted=FALSE",Long.class,user)).isEqualTo(1);
        var restored=ok(call(HttpMethod.GET,"/api/v1/users/me/addresses/"+original.path("id").asText(),token,null),200);
        assertThat(restored.path("defaultAddress").asBoolean()).isTrue();
        assertThat(restored.path("version").asLong()).isEqualTo(original.path("version").asLong());
    }

    /** 审核末步审计失败时，申请、商家、成员、令牌撤销及通知必须在同一注解事务整体回滚。 */
    @Test void annotatedMerchantApprovalAuditFailureRollsBackAllWrites() {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,16),password="Fixture-Password1!";
        String buyer="fixture_rb_"+suffix,platform="fixture_ra_"+suffix,merchant="merchant_rb_"+suffix;
        String user=register(buyer,password),adminId=register(platform,password);
        jdbc.update("INSERT INTO user_role(user_id,role_id) SELECT ?,id FROM role WHERE role_code='PLATFORM_ADMIN'",adminId);
        String token=login(buyer,password),admin=login(platform,password);
        var application=ok(call(HttpMethod.POST,"/api/v1/merchant-applications",token,Map.of("merchantId",merchant,"name","事务回滚商家","description","仅隔离fixture")),201);
        String id=application.path("id").asText(),trigger="reject_review_"+suffix;
        long before=jdbc.queryForObject("SELECT token_version FROM user_account WHERE id=?",Long.class,user);
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture audit failure'");
        try {
            assertThat(call(HttpMethod.POST,"/api/v1/admin/merchant-applications/"+id+"/review",admin,Map.of("approved",true,"reason","应整体回滚","version",0)).getStatusCode().value()).isEqualTo(500);
        } finally {jdbc.execute("DROP TRIGGER "+trigger);}
        assertThat(jdbc.queryForObject("SELECT status FROM merchant_application WHERE id=?",String.class,id)).isEqualTo(application.path("status").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant WHERE merchant_id=?",Long.class,merchant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_member WHERE merchant_id=?",Long.class,merchant)).isZero();
        assertThat(jdbc.queryForObject("SELECT token_version FROM user_account WHERE id=?",Long.class,user)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE user_id=?",Long.class,user)).isZero();
        ok(call(HttpMethod.GET,"/api/v1/users/me",token,null),200);
    }

    /** 邮箱注册通过真实SMTP和Redis：发送限额、验证码必填、已验证时间、唯一邮箱及BCrypt登录形成闭环。 */
    @Test void emailRegistrationRequiresCodeAndPreservesVerifiedIdentity() {
        String email="email_"+UUID.randomUUID().toString().replace("-","")+"@long-domain-for-registration.example.test",password="Fixture-Password1!";
        String code=sendAndReadCode(email);
        var throttled=call(HttpMethod.POST,"/api/v1/auth/email-code",null,Map.of("email",email));
        assertThat(throttled.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(throttled.getHeaders().getFirst("Retry-After"))).isBetween(1L,60L);
        assertThat(call(HttpMethod.POST,"/api/v1/auth/register",null,Map.of("email",email,"password",password,"displayName","买家")).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST,"/api/v1/auth/register",null,Map.of("username","old_bypass","password",password,"displayName","买家")).getStatusCode().value()).isEqualTo(400);
        var body=Map.of("email",email.toUpperCase(Locale.ROOT),"password",password,"displayName","邮箱买家","code",code);
        var user=ok(call(HttpMethod.POST,"/api/v1/auth/register",null,body),201);
        assertThat(user.path("username").asText()).isEqualTo(email);
        assertThat(user.path("roles").toString()).isEqualTo("[\"USER\"]");
        String id=user.path("userId").asText();
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM user_account WHERE id=?",Boolean.class,id)).isTrue();
        String hash=jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id=?",String.class,id);
        assertThat(hash).isNotEqualTo(password);assertThat(passwords.matches(password,hash)).isTrue();
        String token=login(email,password);
        assertThat(ok(call(HttpMethod.GET,"/api/v1/users/me",token,null),200).path("userId").asText()).isEqualTo(id);
        assertThat(call(HttpMethod.POST,"/api/v1/auth/register",null,body).getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username=?",Long.class,email)).isEqualTo(1);
    }

    /** 两个HTTP请求竞争同一验证码，仅一个可以创建账号；消费原子性由真实Redis脚本仲裁。 */
    @Test void concurrentRegistrationCreatesOnlyOneAccount()throws Exception {
        String email="race_"+UUID.randomUUID().toString().replace("-","")+"@example.test";
        var body=Map.of("email",email,"password","Fixture-Password1!","displayName","并发买家","code",sendAndReadCode(email));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var a=pool.submit(()->{start.await();return call(HttpMethod.POST,"/api/v1/auth/register",null,body).getStatusCode().value();});
            var b=pool.submit(()->{start.await();return call(HttpMethod.POST,"/api/v1/auth/register",null,body).getStatusCode().value();});
            start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,400);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username=?",Long.class,email)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }

    /** 注解事务经过真实Spring代理；授角色失败必须回滚账号和验证时间，旧验证码不会因SQL回滚被重新开放。 */
    @Test void registrationRoleFailureRollsBackAccountAndDoesNotRestoreConsumedCode() {
        String email="rollback_"+UUID.randomUUID().toString().replace("-","")+"@example.test";
        var body=Map.of("email",email,"password","Fixture-Password1!","displayName","回滚买家","code",sendAndReadCode(email));
        String trigger="fixture_register_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON user_role FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture role failure'");
        try{assertThat(call(HttpMethod.POST,"/api/v1/auth/register",null,body).getStatusCode().value()).isEqualTo(500);}
        finally{jdbc.execute("DROP TRIGGER "+trigger);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username=?",Long.class,email)).isZero();
        assertThat(call(HttpMethod.POST,"/api/v1/auth/register",null,body).getStatusCode().value()).isEqualTo(400);
    }

    /** V8迁移不把历史账号标为验证邮箱，历史用户名登录继续经过原安全链和BCrypt。 */
    @Test void historicalUsernameLoginStillWorksWithoutEmailVerification() {
        String name="legacy_"+UUID.randomUUID().toString().replace("-","").substring(0,16),password="Fixture-Password1!";
        jdbc.update("INSERT INTO user_account(username,password_hash,display_name) VALUES(?,?,?)",name,passwords.encode(password),"历史用户");
        var result=ok(call(HttpMethod.POST,"/api/v1/auth/login",null,Map.of("username",name,"password",password)),200);
        assertThat(result.path("accessToken").asText()).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM user_account WHERE username=?",Boolean.class,name)).isTrue();
    }

    private void assertLastMerchantAdmin(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();assertThat(response.getBody().path("code").asText()).isEqualTo("LAST_MERCHANT_ADMIN");
    }

    /** 以下辅助方法只组织真实HTTP请求；不模拟数据库、身份或应用事务，不证明Nacos协作。 */
    private String register(String username,String password){
        String email=username+"@example.test";
        return ok(call(HttpMethod.POST,"/api/v1/auth/register",null,Map.of("email",email,"code",sendAndReadCode(email),"password",password,"displayName","fixture用户")),201).path("userId").asText();
    }
    private String login(String username,String password){return ok(call(HttpMethod.POST,"/api/v1/auth/login",null,Map.of("username",username.contains("@")?username:username+"@example.test","password",password)),200).path("accessToken").asText();}
    /** 真正走公开发送接口和JavaMail SMTP，不从Redis取明文或设置测试绕过验证码。 */
    private String sendAndReadCode(String email) {
        smtp.setUser(email,email,"fixture-only");
        assertThat(mailSender.getHost()).isEqualTo("127.0.0.1");
        assertThat(mailSender.getPort()).isEqualTo(smtp.getSmtp().getPort());
        assertThat(mailSender.getJavaMailProperties().getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(mailSender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
        var response=call(HttpMethod.POST,"/api/v1/auth/email-code",null,Map.of("email",email));
        assertThat(response.getStatusCode().value()).as("发送邮件结果 code=%s",response.getBody()==null?"EMPTY":response.getBody().path("code").asText()).isEqualTo(200);
        try {
            for(var message:smtp.getReceivedMessages()) {
                if(!message.getAllRecipients()[0].toString().equals(email))continue;
                var match=java.util.regex.Pattern.compile("[0-9]{6}").matcher(message.getContent().toString());
                if(match.find())return match.group();
            }
        }catch(Exception failure){throw new AssertionError("读取本次隔离SMTP消息失败",failure);}
        throw new AssertionError("SMTP未收到本次邮箱验证码");
    }
    private Map<String,Object> address(String value,boolean isDefault,long version){return Map.of("recipient","fixture收件人","phone","13800000000","region","浙江省杭州市西湖区","address",value,"defaultAddress",isDefault,"version",version);}
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,String token,Object body){return merchantCall(method,path,token,null,body);}
    private ResponseEntity<JsonNode> merchantCall(HttpMethod method,String path,String token,String merchant,Object body){HttpHeaders headers=new HttpHeaders();if(token!=null)headers.setBearerAuth(token);if(merchant!=null)headers.set("X-Merchant-Id",merchant);return http.exchange(path,method,new HttpEntity<>(body,headers),JsonNode.class);}
    private JsonNode ok(ResponseEntity<JsonNode> response,int status){assertThat(response.getStatusCode().value()).as("真实HTTP状态").isEqualTo(status);assertThat(response.getBody()).isNotNull();assertThat(response.getBody().path("code").asText()).isEqualTo("OK");return response.getBody().path("data");}
}
