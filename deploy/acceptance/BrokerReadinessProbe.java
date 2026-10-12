import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.body.KVTable;

/** Read-only probe for the fixed disposable topology, not a production admin client. */
public final class BrokerReadinessProbe {
    public static void main(String[] args) {
        DefaultMQAdminExt admin = new DefaultMQAdminExt(2000L);
        admin.setNamesrvAddr("namesrv:9876");
        admin.setVipChannelEnabled(false);
        admin.setInstanceName("m47-readiness");
        int result = 1;
        try {
            admin.start();
            ClusterInfo cluster = admin.examineBrokerClusterInfo();
            boolean registered = cluster != null && cluster.getBrokerAddrTable() != null
                && cluster.getBrokerAddrTable().values().stream().anyMatch(broker ->
                    "broker:10911".equals(broker.getBrokerAddrs().get(0L)));
            if (registered) {
                KVTable stats = admin.fetchBrokerRuntimeStats("broker:10911");
                if (stats != null && stats.getTable() != null && !stats.getTable().isEmpty()) {
                    result = 0;
                }
            }
        } catch (Exception ignored) {
            // Healthcheck failure is expected during startup. No remote exception bodies.
        } finally {
            try { admin.shutdown(); } catch (Exception ignored) { result = 1; }
        }
        System.out.println(result == 0 ? "M47 broker readiness PASS" : "M47 broker not ready");
        System.exit(result);
    }
}
