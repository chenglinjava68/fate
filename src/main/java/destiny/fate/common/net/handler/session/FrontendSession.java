package destiny.fate.common.net.handler.session;

import destiny.fate.common.net.handler.backend.BackendConnection;
import destiny.fate.common.net.handler.frontend.FrontendConnection;
import destiny.fate.common.net.handler.node.MultiNodeExecutor;
import destiny.fate.common.net.handler.node.ResponseHandler;
import destiny.fate.common.net.handler.node.SingleNodeExecutor;
import destiny.fate.common.net.protocol.util.ErrorCode;
import destiny.fate.common.net.route.RouteResultset;
import destiny.fate.common.net.route.RouteResultsetNode;
import destiny.fate.route.FateStatementParser;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;


/**
 * 前端会话过程
 *
 * @author zhangtianlong
 */
public class FrontendSession implements Session {

    private static final Logger logger = LoggerFactory.getLogger(FrontendSession.class);

    private FrontendConnection source;
    /**
     * 事务是否被打断
     */
    private volatile boolean txInterrupted;
    /**
     * 事务被打断保存的信息
     */
    private volatile String txInterruptMsg = "";

    private final ConcurrentHashMap<RouteResultsetNode, BackendConnection> target;

    private final SingleNodeExecutor singleNodeExecutor;
    private final MultiNodeExecutor multiNodeExecutor;
    /**
     * 处理当前SQL的handler,可以是single也可能是multi
     */
    private ResponseHandler responseHandler;

    public FrontendSession(FrontendConnection source) {
        this.source = source;
        target = new ConcurrentHashMap<RouteResultsetNode, BackendConnection>();
        singleNodeExecutor = new SingleNodeExecutor(this);
        multiNodeExecutor = new MultiNodeExecutor(this);
    }

    public void writeErrMsg(int errNo, String msg) {
        logger.warn(String.format("[FrontendConnection]ErrorNo=%d, ErrorMsg=%s", errNo, msg));
        source.writeErrMessage((byte) 1, errNo, msg);
    }

    public FrontendConnection getSource() {
        return this.source;
    }

    public int getTargetCount() {
        return target.size();
    }

    public void execute(String sql, int type) {
        // 检查状态
        if (txInterrupted) {
            writeErrMsg(ErrorCode.ER_YES, "Transaction errorMessage, need to rollback." + txInterruptMsg);
            return;
        }
        RouteResultset rrs = route(sql, type);
        if (rrs.getNodeCount() == 0) {
            writeErrMsg(ErrorCode.ER_PARSE_ERROR, "parse sql and 0 node get");
            return;
        } else if (rrs.getNodeCount() == 1) {
            responseHandler = singleNodeExecutor;
            singleNodeExecutor.execute(rrs);
        } else {
            responseHandler = multiNodeExecutor;
            multiNodeExecutor.execute(rrs);
        }
    }

    public void commit() {

    }

    public void rollback() {

    }

    public void cancel(FrontendConnection sponsor) {

    }

    public void terminate() {

    }

    public void close() {

    }

    public ChannelHandlerContext getCtx() {
        return source.getCtx();
    }

    private RouteResultset route(String sql, int type) {
        RouteResultset routeResultset = FateStatementParser.parser(sql, type);
        return routeResultset;
    }

    public BackendConnection getTarget(RouteResultsetNode key) {
        BackendConnection backend = target.get(key);
        if (backend == null) {
            backend = source.getStateSyncBackend();
            target.put(key, backend);
        }
        return backend;
    }

    public ResponseHandler getResponseHandler() {
        return responseHandler;
    }

    public void release() {
        for (BackendConnection backend : target.values()) {
            backend.recycle();
        }
        logger.info("session has been release");
        target.clear();
    }
}
