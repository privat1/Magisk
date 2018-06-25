package com.topjohnwu.magisk.asyncs;

import android.app.Activity;
import android.app.ProgressDialog;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import com.topjohnwu.magisk.FlashActivity;
import com.topjohnwu.magisk.MagiskManager;
import com.topjohnwu.magisk.R;
import com.topjohnwu.magisk.container.TarEntry;
import com.topjohnwu.magisk.utils.Const;
import com.topjohnwu.magisk.utils.Utils;
import com.topjohnwu.magisk.utils.ZipUtils;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;
import com.topjohnwu.superuser.io.SuFileInputStream;
import com.topjohnwu.utils.SignBoot;

import org.kamranzafar.jtar.TarInputStream;
import org.kamranzafar.jtar.TarOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;

public class InstallMagisk extends ParallelTask<Void, Void, Boolean> {

    private static final int PATCH_MODE = 0;
    private static final int DIRECT_MODE = 1;
    private static final int FIX_ENV_MODE = 2;

    private Uri bootUri, mZip;
    private List<String> console, logs;
    private String mBoot;
    private int mode;
    private File installDir;
    private ProgressDialog dialog;
    private MagiskManager mm;

    public InstallMagisk(Activity context, Uri zip) {
        super(context);
        mZip = zip;
        mm = MagiskManager.get();
        mode = FIX_ENV_MODE;
    }

    private InstallMagisk(Activity context, List<String> console, List<String> logs, Uri zip) {
        this(context, zip);
        this.console = console;
        this.logs = logs;
    }

    public InstallMagisk(FlashActivity context, List<String> console, List<String> logs, Uri zip, Uri boot) {
        this(context, console, logs, zip);
        bootUri = boot;
        mode = PATCH_MODE;
    }

    public InstallMagisk(FlashActivity context, List<String> console, List<String> logs, Uri zip, String boot) {
        this(context, console, logs, zip);
        mBoot = boot;
        mode = DIRECT_MODE;
    }

    @Override
    protected void onPreExecute() {
        if (mode == FIX_ENV_MODE) {
            dialog = ProgressDialog.show(getActivity(),
                    "Additional Setup", "Running environment setup...");
            console = new NOPList<>();
        }
    }

    private void extractFiles(String arch) throws IOException {
        console.add("- Extracting files");
        try (InputStream in = mm.getContentResolver().openInputStream(mZip)) {
            if (in == null) throw new FileNotFoundException();
            BufferedInputStream buf = new BufferedInputStream(in);
            buf.mark(Integer.MAX_VALUE);
            ZipUtils.unzip(buf, installDir, arch + "/", true);
            buf.reset();
            ZipUtils.unzip(buf, installDir, "common/", true);
            buf.reset();
            ZipUtils.unzip(buf, installDir, "chromeos/", false);
            buf.reset();
            ZipUtils.unzip(buf, installDir, "META-INF/com/google/android/update-binary", true);
            buf.close();
        } catch (FileNotFoundException e) {
            console.add("! Invalid Uri");
            throw e;
        } catch (IOException e) {
            console.add("! Cannot unzip zip");
            throw e;
        }
        Shell.Sync.sh(Utils.fmt("chmod -R 755 %s/*; %s/magiskinit -x magisk %s/magisk",
                installDir, installDir, installDir));
    }

    private boolean dumpBoot() {
        console.add("- Copying boot image to " + mBoot);
        // Copy boot image to local
        try (InputStream in = mm.getContentResolver().openInputStream(bootUri);
             OutputStream out = new FileOutputStream(mBoot)
        ) {
            if (in == null)
                throw new FileNotFoundException();

            InputStream src;
            if (Utils.getNameFromUri(mm, bootUri).endsWith(".tar")) {
                // Extract boot.img from tar
                TarInputStream tar = new TarInputStream(new BufferedInputStream(in));
                org.kamranzafar.jtar.TarEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (entry.getName().equals("boot.img"))
                        break;
                }
                src = tar;
            } else {
                // Direct copy raw image
                src = new BufferedInputStream(in);
            }
            ShellUtils.pump(src, out);
        } catch (FileNotFoundException e) {
            console.add("! Invalid Uri");
            return false;
        } catch (IOException e) {
            console.add("! Copy failed");
            return false;
        }
        return true;
    }

    private File patchBoot() throws IOException {
        boolean isSigned;
        try (InputStream in = new SuFileInputStream(mBoot)) {
            isSigned = SignBoot.verifySignature(in, null);
            if (isSigned) {
                console.add("- Boot image is signed with AVB 1.0");
            }
        } catch (IOException e) {
            console.add("! Unable to check signature");
            throw e;
        }

        // Patch boot image
        Shell.Sync.sh(console, logs,
                "cd " + installDir,
                Utils.fmt("KEEPFORCEENCRYPT=%b KEEPVERITY=%b sh update-binary indep " +
                                "boot_patch.sh %s || echo 'Failed!'",
                        mm.keepEnc, mm.keepVerity, mBoot));

        if (TextUtils.equals(console.get(console.size() - 1), "Failed!"))
            return null;

        Shell.Sync.sh("mv bin/busybox busybox",
                "rm -rf magisk.apk bin boot.img update-binary",
                "cd /");

        File patched = new File(installDir, "new-boot.img");
        if (isSigned) {
            console.add("- Signing boot image with test keys");
            File signed = new File(installDir, "signed.img");
            try (InputStream in = new BufferedInputStream(new FileInputStream(patched));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(signed))
            ) {
                SignBoot.doSignature("/boot", in, out, null, null);
            }
            signed.renameTo(patched);
        }
        return patched;
    }

    private void outputBoot(File patched) throws IOException {
        switch (mode) {
            case PATCH_MODE:
                File dest = new File(Const.EXTERNAL_PATH, "patched_boot" + mm.bootFormat);
                dest.getParentFile().mkdirs();
                OutputStream out;
                switch (mm.bootFormat) {
                    case ".img.tar":
                        out = new TarOutputStream(new BufferedOutputStream(new FileOutputStream(dest)));
                        ((TarOutputStream) out).putNextEntry(new TarEntry(patched, "boot.img"));
                        break;
                    default:
                    case ".img":
                        out = new BufferedOutputStream(new FileOutputStream(dest));
                        break;
                }
                try (InputStream in = new FileInputStream(patched)) {
                    ShellUtils.pump(in, out);
                    out.close();
                }
                console.add("");
                console.add("*********************************");
                console.add(" Patched Boot Image is placed in ");
                console.add(" " + dest + " ");
                console.add("*********************************");
                break;
            case DIRECT_MODE:
                String binPath = mm.remoteMagiskVersionCode >= Const.MAGISK_VER.HIDDEN_PATH ?
                        "/data/adb/magisk" : "/data/magisk";
                Shell.Sync.su(console, logs,
                        Utils.fmt("flash_boot_image %s %s; rm -f %s", patched, mBoot, patched),
                        Utils.fmt("rm -rf %s/*; mkdir -p %s; chmod 700 /data/adb", binPath, binPath),
                        Utils.fmt("cp -af %s/* %s; rm -rf %s", installDir, binPath, installDir),
                        mm.keepVerity ? "" : "patch_dtbo_image");
                break;
        }
        patched.delete();
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        if (mode == FIX_ENV_MODE) {
            installDir = new File("/data/adb/magisk");
            Shell.Sync.sh("rm -rf " + installDir + "/*");
        } else {
            installDir = new File(
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                            mm.createDeviceProtectedStorageContext() : mm)
                            .getFilesDir().getParent()
                    , "install");
            Shell.Sync.sh("rm -rf " + installDir);
            installDir.mkdirs();
        }

        if (mode == PATCH_MODE) {
            mBoot = new File(installDir, "boot.img").getAbsolutePath();
            if (!dumpBoot())
                return false;
        }

        List<String> abis = Arrays.asList(Build.SUPPORTED_ABIS);
        String arch;

        if (mm.remoteMagiskVersionCode >= Const.MAGISK_VER.SEPOL_REFACTOR) {
            // 32-bit only
            if (abis.contains("x86")) arch = "x86";
            else arch = "arm";
        } else {
            if (abis.contains("x86_64")) arch = "x64";
            else if (abis.contains("arm64-v8a")) arch = "arm64";
            else if (abis.contains("x86")) arch = "x86";
            else arch = "arm";
        }

        console.add("- Device platform: " + Build.SUPPORTED_ABIS[0]);

        try {
            extractFiles(arch);
            if (mode == FIX_ENV_MODE) {
                Shell.Sync.sh(
                        "cd " + installDir,
                        "sh update-binary extract",
                        "rm -f update-binary magisk.apk",
                        "cd /",
                        "rm -rf /sbin/.core/busybox/*",
                        "/sbin/.core/mirror/bin/busybox --install -s /sbin/.core/busybox"
                );
            } else {
                File patched = patchBoot();
                if (patched == null)
                    return false;
                outputBoot(patched);
                console.add("- All done!");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (mode == FIX_ENV_MODE) {
            dialog.dismiss();
            MagiskManager.toast(result ? R.string.setup_done : R.string.setup_fail, Toast.LENGTH_LONG);
        } else {
            // Running in FlashActivity
            FlashActivity activity = (FlashActivity) getActivity();
            if (!result) {
                Shell.Async.sh("rm -rf " + installDir);
                console.add("! Installation failed");
                activity.reboot.setVisibility(View.GONE);
            }
            activity.buttonPanel.setVisibility(View.VISIBLE);
        }
    }

    private static class NOPList<E> extends AbstractList<E> {
        @Override
        public E get(int index) {
            return null;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public void add(int index, E element) {}
    }
}