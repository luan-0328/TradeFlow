package com.tradeflow.settlement;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/** 独立模拟结算服务，绝不调用真实出款通道。 */
@SpringBootApplication @EnableScheduling
public class SettlementApplication { public static void main(String[] args){SpringApplication.run(SettlementApplication.class,args);} }
