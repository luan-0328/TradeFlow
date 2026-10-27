package com.tradeflow.observability;

import io.micrometer.core.instrument.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;

/** 独立后台线程采样，HTTP抓取只读内存；不占业务恢复调度线程，不持业务锁，不写数据库。 */
final class BusinessMetricsSampler implements ApplicationListener<ApplicationReadyEvent>,AutoCloseable {
 private static final Logger LOG=LoggerFactory.getLogger(BusinessMetricsSampler.class);
 private record Snapshot(boolean valid,Map<String,double[]> groups,double locks,double lastSuccess){}
 private final JdbcTemplate db;private final List<BusinessMetricCatalog.Query> queries;private final Counter errors;
 private final AtomicBoolean started=new AtomicBoolean();private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"tradeflow-metrics-sampler");t.setDaemon(true);return t;});
 private volatile Snapshot snapshot=new Snapshot(false,Map.of(),Double.NaN,0);

 /** 固定注册指标集合；每次查询至多两秒，标签集合在启动时冻结，不把业务编号加入指标。 */
 BusinessMetricsSampler(DataSource source,MeterRegistry registry,String code){db=new JdbcTemplate(source);db.setQueryTimeout(2);queries=BusinessMetricCatalog.forService(code);errors=Counter.builder("tradeflow.metrics.collection.errors").tag("collector","business").register(registry);
  for(var query:queries){gauge(registry,"tradeflow.business.backlog",query.kind(),0);gauge(registry,"tradeflow.business.oldest.age.seconds",query.kind(),1);gauge(registry,"tradeflow.business.due.lag.seconds",query.kind(),2);}
  Gauge.builder("tradeflow.metrics.collection.success",this,s->s.snapshot.valid()?1:0).tag("collector","business").register(registry);
  Gauge.builder("tradeflow.metrics.last.success.unixtime.seconds",this,s->s.snapshot.lastSuccess()).tag("collector","business").register(registry);
  Gauge.builder("tradeflow.database.row.lock.waits",this,s->s.snapshot.valid()?s.snapshot.locks():Double.NaN).tag("scope","instance").register(registry);
 }
 /** 抓取回调只读取最近完整快照，失败或尚未首次采样输出NaN，不能展示成业务积压为零。 */
 private void gauge(MeterRegistry registry,String name,String kind,int field){Gauge.builder(name,this,s->{var current=s.snapshot;var row=current.groups().get(kind);return current.valid()&&row!=null?row[field]:Double.NaN;}).tag("kind",kind).register(registry);}
 /** Spring与管理子上下文事件只启动一次；采样与恢复任务完全独立，避免指标SQL拖慢资金任务。 */
 public void onApplicationEvent(ApplicationReadyEvent event){if(started.compareAndSet(false,true))executor.scheduleWithFixedDelay(this::collect,5,30,TimeUnit.SECONDS);}
 /** 查询全部成功后原子替换快照；任何异常保留最后成功时间并使所有业务值无效，不泄露SQL或异常原文。 */
 private void collect(){try{Map<String,double[]> groups=new HashMap<>();for(var query:queries){var row=db.queryForMap(query.sql());groups.put(query.kind(),new double[]{number(row,"backlog"),number(row,"age"),number(row,"due_lag")});}var status=db.queryForMap("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_current_waits'");double locks=Double.parseDouble(Objects.toString(status.get("Value")));snapshot=new Snapshot(true,Map.copyOf(groups),locks,Instant.now().toEpochMilli()/1000.0);}catch(Exception failure){var old=snapshot;snapshot=new Snapshot(false,old.groups(),Double.NaN,old.lastSuccess());errors.increment();LOG.warn("event=METRICS_COLLECTION_FAILED collector=business errorType={}",failure.getClass().getSimpleName());}}
 private static double number(Map<String,Object> row,String key){return ((Number)row.get(key)).doubleValue();}
 /** 应用停止只关闭本采样器，不中断业务工作线程或其他服务。 */
 public void close(){executor.shutdownNow();}
}
