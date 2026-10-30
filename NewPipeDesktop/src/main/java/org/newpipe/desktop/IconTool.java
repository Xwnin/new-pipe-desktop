package org.newpipe.desktop;

import org.newpipe.desktop.ui.Icons;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Build helper: writes the NewPipe logo as a multi-size PNG-compressed Windows .ico (used by jpackage). */
public final class IconTool {
    private IconTool() {}

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "build/app.ico");
        out.getParentFile().mkdirs();
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(Icons.logo(s), "png", bos);
            pngs.add(bos.toByteArray());
        }
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(out))) {
            ByteBuffer head = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
            head.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
            int offset = 6 + 16 * sizes.length;
            for (int i = 0; i < sizes.length; i++) {
                head.put((byte) (sizes[i] >= 256 ? 0 : sizes[i])).put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
                head.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
                head.putInt(pngs.get(i).length).putInt(offset);
                offset += pngs.get(i).length;
            }
            o.write(head.array());
            for (byte[] p : pngs) o.write(p);
        }
        System.out.println("wrote " + out.getAbsolutePath());
        System.exit(0);
    }
}
