/** Run against the actual broker classpath; no mocked memory/management results. */
public final class BrokerRuntimeProbe {
    public static void main(String[] args) throws Exception {
        Class<?> store = Class.forName("org.apache.rocketmq.store.StoreUtil");
        long memory = store.getField("TOTAL_PHYSICAL_MEMORY_SIZE").getLong(null);
        if (memory <= 0) throw new IllegalStateException("Broker physical memory probe failed");
        System.out.println("Broker StoreUtil initialization PASS; java=" + System.getProperty("java.runtime.version"));
    }
}
