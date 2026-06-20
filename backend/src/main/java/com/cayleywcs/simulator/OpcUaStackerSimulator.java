package com.cayleywcs.simulator;

import com.cayleywcs.simulator.opcua.StackerNamespace;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.security.DefaultTrustListManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.server.security.DefaultServerCertificateValidator;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 堆垛机 OPC UA 仿真服务端（Eclipse Milo），仅在 simulator profile 下启用。
 * 以 None 安全 + 匿名暴露 DB100 节点（NodeId 形如 ns=2;s=WCS_Task.From_WCS.WCS_Heart），
 * 节点读写直连内存 {@link StackerDeviceState}；自身按节拍 tick(翻转 PLC 心跳/推进握手)。
 * 把某应用的 OPC UA endpoint 指向本服务端，即可无硬件做真·OPC UA 端到端联调。
 */
@Component
@Profile("simulator")
public class OpcUaStackerSimulator {
    private static final Logger log = LoggerFactory.getLogger(OpcUaStackerSimulator.class);

    /** 仿真设备在注册表中的固定 appId，便于 /simulator/inject-fault 注入故障。 */
    public static final long REGISTRY_APP_ID = 999001L;

    private final StackerSimulatorRegistry registry;
    private final int port;
    private final String bindHost;
    private final String path;

    private OpcUaServer server;
    private ScheduledExecutorService ticker;

    public OpcUaStackerSimulator(StackerSimulatorRegistry registry,
                                 @Value("${cayleywcs.simulator.opcua.port:4840}") int port,
                                 @Value("${cayleywcs.simulator.opcua.host:127.0.0.1}") String bindHost,
                                 @Value("${cayleywcs.simulator.opcua.path:/cayleywcs/stacker}") String path) {
        this.registry = registry;
        this.port = port;
        this.bindHost = bindHost;
        this.path = path;
    }

    @PostConstruct
    public void start() throws Exception {
        StackerDeviceState device = registry.getOrCreate(REGISTRY_APP_ID);

        var tmp = Files.createTempDirectory("wcs-milo-pki").toFile();
        var certificateManager = new DefaultCertificateManager();
        var trustListManager = new DefaultTrustListManager(tmp);
        var certificateValidator = new DefaultServerCertificateValidator(trustListManager);

        EndpointConfiguration endpoint = EndpointConfiguration.newBuilder()
                .setBindAddress("0.0.0.0")
                .setHostname(bindHost)
                .setPath(path)
                .setSecurityPolicy(SecurityPolicy.None)
                .setSecurityMode(MessageSecurityMode.None)
                .addTokenPolicy(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS)
                .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                .setBindPort(port)
                .build();

        OpcUaServerConfig config = OpcUaServerConfig.builder()
                .setApplicationUri("urn:cayleywcs:stacker:simulator")
                .setApplicationName(LocalizedText.english("CayleyWCS Stacker OPC UA Simulator"))
                .setEndpoints(Set.of(endpoint))
                .setBuildInfo(new BuildInfo("urn:cayleywcs", "CayleyWCS", "Stacker Simulator",
                        "0.1.0", "", DateTime.now()))
                .setCertificateManager(certificateManager)
                .setTrustListManager(trustListManager)
                .setCertificateValidator(certificateValidator)
                .setIdentityValidator(new AnonymousIdentityValidator())
                .setProductUri("urn:cayleywcs:stacker")
                .build();

        server = new OpcUaServer(config);
        StackerNamespace namespace = new StackerNamespace(server, device);
        namespace.startup();
        server.startup().get();

        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "opcua-sim-tick");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(device::tick, 1, 1, TimeUnit.SECONDS);

        log.info("OPC UA 仿真器已启动：opc.tcp://{}:{}{}  (ns={}, registryAppId={})",
                bindHost, port, path, namespace.getNamespaceIndex(), REGISTRY_APP_ID);
    }

    @PreDestroy
    public void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        if (server != null) {
            try {
                server.shutdown().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 关闭期间忽略
            }
        }
    }

    /** 协议点位字段定义：group(From_WCS/To_WCS)、field、是否 REAL。 */
    public record Field(String group, String field, boolean real) {
    }

    public static final List<Field> FIELDS = List.of(
            new Field("From_WCS", "WCS_Heart", false),
            new Field("From_WCS", "cmd_TaskType", false),
            new Field("From_WCS", "cmd_ResetFault", false),
            new Field("From_WCS", "cmd_TakeCoor_Row", false),
            new Field("From_WCS", "cmd_TakeCoor_Column", false),
            new Field("From_WCS", "cmd_TakeCoor_Floor", false),
            new Field("From_WCS", "cmd_PutCoor_Row", false),
            new Field("From_WCS", "cmd_PutCoor_Column", false),
            new Field("From_WCS", "cmd_PutCoor_Floor", false),
            new Field("From_WCS", "cmd_PortNum", false),
            new Field("From_WCS", "cmd_TaskNum", false),
            new Field("From_WCS", "cmd_ConfirmTask", false),
            new Field("To_WCS", "PLC_Heart", false),
            new Field("To_WCS", "status_Mode", false),
            new Field("To_WCS", "status_Task", false),
            new Field("To_WCS", "status_TaskTypeFeedback", false),
            new Field("To_WCS", "status_CurrentColumnNum", false),
            new Field("To_WCS", "status_CurrentFloorNum", false),
            new Field("To_WCS", "status_Speed_Lift", true),
            new Field("To_WCS", "status_Speed_Walk", true),
            new Field("To_WCS", "status_Speed_Fork", true),
            new Field("To_WCS", "status_CurrentPos_Walk", true),
            new Field("To_WCS", "status_CurrentPos_Lift", true),
            new Field("To_WCS", "status_CurrentPos_Fork", true),
            new Field("To_WCS", "status_TaskNum", false),
            new Field("To_WCS", "status_Cargo", false),
            new Field("To_WCS", "status_ErrorCode", false)
    );
}
