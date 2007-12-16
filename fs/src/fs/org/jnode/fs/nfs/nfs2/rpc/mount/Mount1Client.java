package org.jnode.fs.nfs.nfs2.rpc.mount;

import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;

import org.acplt.oncrpc.OncRpcClient;
import org.acplt.oncrpc.OncRpcClientAuthUnix;
import org.acplt.oncrpc.OncRpcException;
import org.acplt.oncrpc.XdrAble;
import org.acplt.oncrpc.XdrDecodingStream;
import org.acplt.oncrpc.XdrEncodingStream;
import org.acplt.oncrpc.XdrVoid;
import org.apache.log4j.Logger;

/**
 * 
 * @author Andrei Dore
 */
public class Mount1Client {

    private static final Logger LOGGER = Logger.getLogger(Mount1Client.class);

    private static final int MOUNT_CODE = 100005;
    private static final int MOUNT_VERSION = 1;

    private static final int PROCEDURE_TEST = 0;
    private static final int PROCEDURE_MOUNT = 1;
    private static final int PROCEDURE_UNMOUNT = 3;

    public static final int FILE_HANDLE_SIZE = 32;
    public static final int MAX_PATH_LENGHT = 1024;
    public static final int MAX_NAME_LENGHT = 255;

    public static final int MOUNT_OK = 0;
    private InetAddress host;
    private int protocol;
    private int uid;
    private int gid;

    private List<OncRpcClient> rpcClientPool;

    private final Object rpcCLientLock = new Object();

    /**
     * Constructs a <code>Mount1Client</code> client stub proxy object from
     * which the MOUNTPROG remote program can be accessed.
     * 
     * @param host
     *                Internet address of host where to contact the remote
     *                program.
     * @param protocol
     *                {@link org.acplt.oncrpc.OncRpcProtocols Protocol} to be
     *                used for ONC/RPC calls.
     */
    public Mount1Client(InetAddress host, int protocol, int uid, int gid) {
        this.host = host;
        this.protocol = protocol;
        this.uid = uid;
        this.gid = gid;

        rpcClientPool = new LinkedList<OncRpcClient>();

    }

    private OncRpcClient createRpcClient() throws OncRpcException, IOException {

        OncRpcClient client = OncRpcClient.newOncRpcClient(host, MOUNT_CODE,
                MOUNT_VERSION, protocol);
        client.setTimeout(10000);
        if (uid != -1 && gid != -1) {
            client.setAuth(new OncRpcClientAuthUnix("test", uid, gid));
        }

        return client;
    }

    private OncRpcClient getRpcClient() throws OncRpcException, IOException {

        synchronized (rpcCLientLock) {

            if (rpcClientPool.size() == 0) {
                return createRpcClient();
            } else {
                return rpcClientPool.remove(0);

            }

        }

    }

    private synchronized void releaseRpcClient(OncRpcClient client) {
        synchronized (rpcCLientLock) {

            if (client != null) {
                rpcClientPool.add(client);
            }
        }

    }

    /**
     * Call remote procedure test.
     * 
     * @throws OncRpcException
     *                 if an ONC/RPC error occurs.
     * @throws IOException
     *                 if an I/O error occurs.
     * @throws MountException
     */
    public void test() throws IOException, MountException {
        call(PROCEDURE_TEST, XdrVoid.XDR_VOID, XdrVoid.XDR_VOID);
    }

    /**
     * Call remote procedure mount.
     * 
     * @param dirPath
     *                parameter (of type DirPath) to the remote procedure call.
     * @return Result from remote procedure call (of type MountResult).
     * @throws OncRpcException
     *                 if an ONC/RPC error occurs.
     * @throws IOException
     *                 if an I/O error occurs.
     * @throws MountException
     */
    public MountResult mount(final String path) throws IOException,
            MountException {

        XdrAble mountParameter = new Parameter() {

            public void xdrEncode(XdrEncodingStream xdrEncodingStream)
                    throws OncRpcException, IOException {
                xdrEncodingStream.xdrEncodeString(path);
            }

        };

        final MountResult result = new MountResult();

        XdrAble mountResult = new Result() {

            public void xdrDecode(XdrDecodingStream xdrDecodingStream)
                    throws OncRpcException, IOException {
                result.setFileHandle(xdrDecodingStream
                        .xdrDecodeOpaque(Mount1Client.FILE_HANDLE_SIZE));

            }
        };

        call(PROCEDURE_MOUNT, mountParameter, mountResult);

        return result;
    }

    public void unmount(final String dirPath) throws IOException,
            MountException {

        XdrAble mountParameter = new Parameter() {

            public void xdrEncode(XdrEncodingStream xdrEncodingStream)
                    throws OncRpcException, IOException {
                xdrEncodingStream.xdrEncodeString(dirPath);
            }

        };

        call(PROCEDURE_UNMOUNT, mountParameter, XdrVoid.XDR_VOID);

    }

    private void call(final int functionId, final XdrAble parameter,
            final XdrAble result) throws MountException, IOException {

        int countCall = 0;

        OncRpcClient client = null;

        while (true) {
            try {

                countCall++;

                client = getRpcClient();

                if (result == XdrVoid.XDR_VOID) {

                    client.call(functionId, parameter, result);

                } else {

                    ResultWithCode mountResult = new ResultWithCode(result);

                    client.call(functionId, parameter, mountResult);

                    if (mountResult.getResultCode() != 0) {
                        throw new MountException(
                                "An error occur when system try to mount . Error code it is "
                                        + mountResult.getResultCode());
                    }

                }

                break;

            } catch (Exception e) {

                // if we receive a timeout we will close the client and next
                // time we will use another rpc client
                if (client != null) {
                    try {
                        client.close();
                    } catch (OncRpcException e1) {
                        // Ignore this
                    }
                    client = null;
                }

                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }

                if (e instanceof IOException || e instanceof OncRpcException) {

                    if (countCall > 10) {
                        throw new MountException(e.getMessage(), e);
                    } else {
                        LOGGER
                                .warn("An error occurs when nfs file system try to call the rpc method:"
                                        + e.getMessage() + " It will try again");
                        continue;
                    }

                } else {
                    throw new MountException(e.getMessage(), e);
                }

            } finally {

                if (client != null) {
                    releaseRpcClient(client);
                }

            }
        }
    }

    private abstract class Parameter implements XdrAble {

        public void xdrDecode(XdrDecodingStream arg0) throws OncRpcException,
                IOException {

        }

    }

    private abstract class Result implements XdrAble {

        public void xdrEncode(XdrEncodingStream arg0) throws OncRpcException,
                IOException {

        }

    }

    private class ResultWithCode implements XdrAble {

        private int resultCode;
        private XdrAble xdrAble;

        public ResultWithCode(XdrAble xdrAble) {
            this.xdrAble = xdrAble;
        }

        public void xdrEncode(XdrEncodingStream xdr) throws OncRpcException,
                IOException {
        }

        public void xdrDecode(XdrDecodingStream xdr) throws OncRpcException,
                IOException {
            resultCode = xdr.xdrDecodeInt();
            if (resultCode == 0) {
                xdrAble.xdrDecode(xdr);
            }

        }

        public int getResultCode() {
            return resultCode;
        }

    }

    public void close() {
        for (int i = 0; i < rpcClientPool.size(); i++) {

            OncRpcClient client = rpcClientPool.get(i);
            try {
                client.close();
            } catch (OncRpcException e) {

            }

        }

    }

}