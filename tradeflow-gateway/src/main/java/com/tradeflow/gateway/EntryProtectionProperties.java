package com.tradeflow.gateway;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 所有网关实例必须使用相同Redis、前缀及窗口配置；不允许关闭共享保护后静默放行。 */
@Component
@ConfigurationProperties(prefix="tradeflow.entry-protection")
public class EntryProtectionProperties {
    private String keyPrefix="tradeflow:entry:v1";
    private long timeoutMillis=500;
    private Map<String,Rule> rules=Map.of(
            "login",new Rule(60,30,300), "order",new Rule(60,20,200),
            "query",new Rule(60,180,3000), "recovery",new Rule(60,10,100),
            "write",new Rule(60,60,600));
    public String getKeyPrefix(){return keyPrefix;}
    public void setKeyPrefix(String value){keyPrefix=value;}
    public long getTimeoutMillis(){return timeoutMillis;}
    public void setTimeoutMillis(long value){if(value<1 || value>10000)throw new IllegalArgumentException("入口依赖超时须为1至10000毫秒");timeoutMillis=value;}
    public Map<String,Rule> getRules(){return rules;}
    public void setRules(Map<String,Rule> value){rules=value;}
    /** 启动即拒绝非法或缺失限额，禁止运行到请求阶段才失去可预测的保护语义。 */
    @jakarta.annotation.PostConstruct
    public void validate(){
        if(keyPrefix==null || !keyPrefix.matches("[a-zA-Z0-9:_-]{1,120}"))throw new IllegalArgumentException("入口Redis前缀不合法");
        for(String category:java.util.List.of("login","order","query","recovery","write"))rule(category);
    }
    public Rule rule(String category){
        Rule rule=rules.get(category);
        if(rule==null || rule.windowSeconds<1 || rule.windowSeconds>86400 || rule.perPrincipal<1 || rule.perPrincipal>1000000 || rule.total<1 || rule.total>1000000)
            throw new IllegalStateException("入口限额配置缺失或非法: "+category);
        return rule;
    }
    public static class Rule {
        private int windowSeconds;
        private int perPrincipal;
        private int total;
        public Rule(){}
        public Rule(int windowSeconds,int perPrincipal,int total){this.windowSeconds=windowSeconds;this.perPrincipal=perPrincipal;this.total=total;}
        public int getWindowSeconds(){return windowSeconds;}
        public void setWindowSeconds(int value){windowSeconds=value;}
        public int getPerPrincipal(){return perPrincipal;}
        public void setPerPrincipal(int value){perPrincipal=value;}
        public int getTotal(){return total;}
        public void setTotal(int value){total=value;}
    }
}
