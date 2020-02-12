package jnr.posix;

import jnr.constants.platform.*;
import jnr.posix.util.Platform;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Created by headius on 5/31/14.
 */
public class IOTest {
    private static POSIX posix;

    @BeforeClass
    public static void setUpClass() throws Exception {
        posix = POSIXFactory.getPOSIX(new DummyPOSIXHandler(), true);
    }

    @Test
    public void testOpenReadWrite() throws Throwable {
        if (!Platform.IS_WINDOWS) {
            File tmp = File.createTempFile("IOTest", "testOpen");
            int fd = posix.open(tmp.getPath(), OpenFlags.O_RDWR.intValue(), 0666);
            Assert.assertTrue(fd > 0);

            byte[] hello = "hello".getBytes();
            int written = posix.write(fd, hello, 5);
            Assert.assertEquals(5, written);

            byte[] buf = new byte[5];
            posix.lseekLong(fd, 0, 0); // no jnr-constants for SEEK_SET
            int read = posix.read(fd, buf, 5);
            Assert.assertEquals(5, read);
            Assert.assertArrayEquals(hello, buf);

            byte[] goodbye = "goodbye".getBytes();
            written = posix.pwrite(fd, goodbye, 7, 3);
            Assert.assertEquals(7, written);
            Assert.assertEquals(5, posix.lseekLong(fd, 0, 1)); // SEEK_CUR

            byte[] bye = new byte[3];
            read = posix.pread(fd, bye, 3, 7);
            Assert.assertEquals(3, read);
            Assert.assertEquals(5, posix.lseekLong(fd, 0, 1)); // SEEK_CUR
            Assert.assertArrayEquals("bye".getBytes(), bye);
        }
    }

    @Test
    public void testPipe() throws Throwable {
        int[] fds = {0, 0};
        int ret = posix.pipe(fds);
        Assert.assertTrue(ret >= 0);
        Assert.assertTrue(fds[0] > 0);
        Assert.assertTrue(fds[1] > 0);

        byte[] hello = "hello".getBytes();
        int written = posix.write(fds[1], hello, 5);
        Assert.assertEquals(5, written);

        byte[] buf = new byte[5];
        int read = posix.read(fds[0], buf, 5);
        Assert.assertEquals(5, read);
        Assert.assertArrayEquals(buf, hello);
    }

    @Test
    public void testSocketPair() throws Throwable {
        if (!Platform.IS_WINDOWS) {
            int[] fds = {0, 0};

            int ret = posix.socketpair(AddressFamily.AF_UNIX.intValue(), Sock.SOCK_STREAM.intValue(), 0, fds);

            Assert.assertTrue(ret >= 0);
            Assert.assertTrue(fds[0] > 0);
            Assert.assertTrue(fds[1] > 0);

            byte[] hello = "hello".getBytes();
            int written = posix.write(fds[1], hello, 5);
            Assert.assertEquals(5, written);

            byte[] buf = new byte[5];
            int read = posix.read(fds[0], buf, 5);
            Assert.assertEquals(5, read);
            Assert.assertArrayEquals(buf, hello);

            hello = "goodbye".getBytes();
            written = posix.write(fds[0], hello, 7);
            Assert.assertEquals(7, written);

            buf = new byte[7];
            read = posix.read(fds[1], buf, 7);
            Assert.assertEquals(7, read);
            Assert.assertArrayEquals(buf, hello);
        }
    }

    @Test
    public void testSendRecvMsg_NoControl() throws Throwable {
        if (!Platform.IS_WINDOWS) {
            int[] fds = {0, 0};

            int ret = posix.socketpair(AddressFamily.AF_UNIX.intValue(), Sock.SOCK_STREAM.intValue(), 0, fds);

            Assert.assertTrue(ret >= 0);
            Assert.assertTrue(fds[0] > 0);
            Assert.assertTrue(fds[1] > 0);

            MsgHdr outMessage = posix.allocateMsgHdr();

            String data = "does this work?";
            byte[] dataBytes = data.getBytes();

            ByteBuffer[] outIov = new ByteBuffer[1];
            outIov[0] = ByteBuffer.allocateDirect(dataBytes.length);
            outIov[0].put(dataBytes);
            outIov[0].flip();

            outMessage.setIov(outIov);

            int sendStatus = posix.sendmsg(fds[0], outMessage, 0);

            Assert.assertTrue(sendStatus == dataBytes.length);

            // ----------------

            MsgHdr inMessage = posix.allocateMsgHdr();
            ByteBuffer[] inIov = new ByteBuffer[1];
            inIov[0] = ByteBuffer.allocateDirect(1024);
            inMessage.setIov(inIov);

            int recvStatus = posix.recvmsg(fds[1], inMessage, 0);

            Assert.assertTrue(recvStatus == dataBytes.length);

            for (int i = 0; i < recvStatus; ++i) {
                Assert.assertEquals(dataBytes[i], outIov[0].get(i));
            }
        }
    }

    @Test
    public void testSendRecvMsg_WithControl() throws Throwable {
        if (!Platform.IS_WINDOWS) {
            int[] fds = {0, 0};

            int ret = posix.socketpair(AddressFamily.AF_UNIX.intValue(), Sock.SOCK_STREAM.intValue(), 0, fds);

            String data = "does this work?";
            byte[] dataBytes = data.getBytes();


            Assert.assertTrue(ret >= 0);
            Assert.assertTrue(fds[0] > 0);
            Assert.assertTrue(fds[1] > 0);

            MsgHdr outMessage = posix.allocateMsgHdr();

            ByteBuffer[] outIov = new ByteBuffer[1];
            outIov[0] = ByteBuffer.allocateDirect(dataBytes.length);
            outIov[0].put(dataBytes);
            outIov[0].flip();

            outMessage.setIov(outIov);

            CmsgHdr outControl = outMessage.allocateControl(4);
            outControl.setLevel(SocketLevel.SOL_SOCKET.intValue());
            outControl.setType(0x01);

            ByteBuffer fdBuf = ByteBuffer.allocateDirect(4);
            fdBuf.order(ByteOrder.nativeOrder());
            fdBuf.putInt(0, fds[0]);
            outControl.setData(fdBuf);

            int sendStatus = posix.sendmsg(fds[0], outMessage, 0);

            Assert.assertTrue(sendStatus == dataBytes.length);

            // ----------------

            MsgHdr inMessage = posix.allocateMsgHdr();
            ByteBuffer[] inIov = new ByteBuffer[1];
            inIov[0] = ByteBuffer.allocateDirect(1024);
            inMessage.setIov(inIov);

            inMessage.allocateControl(4);
            int recvStatus = posix.recvmsg(fds[1], inMessage, 0);

            Assert.assertTrue(recvStatus == dataBytes.length);

            ByteBuffer inFdBuf = inMessage.getControls()[0].getData();
            inFdBuf.order(ByteOrder.nativeOrder());

            int fd = inFdBuf.getInt();

            Assert.assertTrue(fd != 0);

            for (int i = 0; i < recvStatus; ++i) {
                Assert.assertEquals(dataBytes[i], outIov[0].get(i));
            }

        }
    }

    @Test
    public void testSendRecvMsg_LotsOfDataControl() throws Throwable {
        if (!Platform.IS_WINDOWS) {
            int numberOfThreads = 1000;
            int numberOfMessages = 10000;

            List<Thread> threads = new ArrayList<Thread>();

            for( int x = 0; x < numberOfThreads; x++ ){
                threads.add( new Thread( new SocketSender( numberOfMessages ) ) );
            }

            for( Thread th : threads ){
                th.start();
            }

            for( Thread th : threads ){
                th.join(10000);
            }
        }
    }

    private class SocketSender implements Runnable {

        private int m_numberOfTimes;
        private int[] m_fds = {0, 0};
        private Random m_random;

        SocketSender( int numberOfTimes ){
            m_numberOfTimes = numberOfTimes;
            m_random = new Random();

            int ret = posix.socketpair(AddressFamily.AF_UNIX.intValue(), Sock.SOCK_STREAM.intValue(), 0, m_fds);

            Assert.assertTrue(ret >= 0);
            Assert.assertTrue(m_fds[0] > 0);
            Assert.assertTrue(m_fds[1] > 0);

            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.order(ByteOrder.nativeOrder());
            buf.putInt(1).flip();

            ret = posix.libc().setsockopt(m_fds[1],
                SocketLevel.SOL_SOCKET.intValue(),
                jnr.constants.platform.SocketOption.SO_PASSCRED.intValue(),
                buf,
                buf.remaining());

            Assert.assertTrue(ret >= 0);
        }

        @Override
        public void run() {
            for( int x = 0; x < m_numberOfTimes; x++ ){
                MsgHdr outMessage = posix.allocateMsgHdr();

                String data = "does this work?";
                byte[] dataBytes = data.getBytes();

                ByteBuffer[] outIov = new ByteBuffer[1];
                outIov[0] = ByteBuffer.allocateDirect(dataBytes.length);
                outIov[0].put(dataBytes);
                outIov[0].flip();

                outMessage.setIov(outIov);

                int sendStatus = posix.sendmsg(m_fds[0], outMessage, 0);

                Assert.assertTrue(sendStatus == dataBytes.length);

                // ----------------

                MsgHdr inMessage = posix.allocateMsgHdr();
                ByteBuffer[] inIov = new ByteBuffer[1];
                inIov[0] = ByteBuffer.allocateDirect(m_random.nextInt( 5000 ) + 100 );
                inMessage.setIov(inIov);
                inMessage.allocateControl(512);

                int recvStatus = posix.recvmsg(m_fds[1], inMessage, 0);

                Assert.assertTrue(recvStatus == dataBytes.length);

                // Side-effect of toString needed for this test.
                // 2020-02-12: the toString method can cause glibc to fail with
                // an 'unsorted double linked list corrupted' error
                inMessage.toString();

                for (int i = 0; i < recvStatus; ++i) {
                    Assert.assertEquals(dataBytes[i], outIov[0].get(i));
                }
            }
        }

    }
}
