import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

// Usage: java -jar patcher.jar <path to projectzomboid.jar>
// Rewrites three game classes in place with the JDK's ClassFile API and adds ZomboidRadioHook to the jar.
// Nothing from the game is shipped; everything is patched from the player's own jar.
public class ZomboidRadioPatcher {
   static final String HOOK_ENTRY = "zombie/radio/devices/ZomboidRadioHook.class";
   static final ClassDesc HOOK = ClassDesc.ofInternalName("zombie/radio/devices/ZomboidRadioHook");

   public static void main(String[] args) throws Exception {
      Path jar = Path.of(args[0]);
      try (FileSystem fs = FileSystems.newFileSystem(jar);
           URLClassLoader gameClasses = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
         if (Files.exists(fs.getPath(HOOK_ENTRY))) {
            System.out.println("Already patched. (Steam > Verify integrity of game files undoes it.)");
            return;
         }

         ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(gameClasses))));

         byte[] emitter = patch(cf, fs, "fmod/fmod/FMODSoundEmitter", "addSound",
            swap("fmod/fmod/FMODManager", "loadSound", "(Ljava/lang/String;)J", "loadSound"),
            swap("fmod/javafmod", "FMOD_System_PlaySound", "(JZ)J", "playSound"));
         byte[] fileSound = patch(cf, fs, "fmod/fmod/FMODSoundEmitter$FileSound", "stop",
            swap("fmod/javafmod", "FMOD_Channel_Stop", "(J)I", "channelStop"));
         byte[] deviceData = patch(cf, fs, "zombie/radio/devices/DeviceData", "updateEmitter", new Counted() {
            @Override
            public void atStart(CodeBuilder b) {
               Label original = b.newLabel();
               b.aload(0)
                  .invokestatic(HOOK, "updateEmitter",
                     MethodTypeDesc.of(ConstantDescs.CD_boolean, ClassDesc.ofInternalName("zombie/radio/devices/DeviceData")))
                  .ifeq(original)
                  .return_()
                  .labelBinding(original);
               hits++;
            }
         });

         // write only after every patch applied and verified, so a changed game version leaves the jar untouched
         Files.write(fs.getPath("fmod/fmod/FMODSoundEmitter.class"), emitter);
         Files.write(fs.getPath("fmod/fmod/FMODSoundEmitter$FileSound.class"), fileSound);
         Files.write(fs.getPath("zombie/radio/devices/DeviceData.class"), deviceData);
         try (var hook = ZomboidRadioPatcher.class.getResourceAsStream("/" + HOOK_ENTRY)) {
            Files.write(fs.getPath(HOOK_ENTRY), hook.readAllBytes());
         }
      }

      System.out.println("Patched. Tune any radio to 91.8 MHz in game.");
   }

   static byte[] patch(ClassFile cf, FileSystem fs, String cls, String method, Counted... transforms) throws Exception {
      ClassModel model = cf.parse(Files.readAllBytes(fs.getPath(cls + ".class")));
      CodeTransform all = transforms[0];
      for (int i = 1; i < transforms.length; i++) {
         all = all.andThen(transforms[i]);
      }

      byte[] out = cf.transformClass(model, ClassTransform.transformingMethodBodies(m -> m.methodName().equalsString(method), all));
      for (Counted t : transforms) {
         if (t.hits != 1) {
            throw new IllegalStateException(cls + "." + method + " doesn't look like expected (" + t.hits + " matches) - game version not supported");
         }
      }

      var errors = cf.verify(out);
      if (!errors.isEmpty()) {
         throw new IllegalStateException(cls + " failed verification: " + errors);
      }

      return out;
   }

   // Replaces a call owner.name(desc) with the same-shaped static call ZomboidRadioHook.hookName (receiver becomes 1st arg)
   static Counted swap(String owner, String name, String desc, String hookName) {
      return new Counted() {
         @Override
         public void accept(CodeBuilder b, CodeElement e) {
            if (e instanceof InvokeInstruction i
               && i.owner().asInternalName().equals(owner)
               && i.name().equalsString(name)
               && i.type().equalsString(desc)) {
               MethodTypeDesc type = MethodTypeDesc.ofDescriptor(desc);
               b.invokestatic(HOOK, hookName, i.opcode() == Opcode.INVOKESTATIC ? type : type.insertParameterTypes(0, i.owner().asSymbol()));
               hits++;
            } else {
               b.with(e);
            }
         }
      };
   }

   abstract static class Counted implements CodeTransform {
      int hits;

      @Override
      public void accept(CodeBuilder b, CodeElement e) {
         b.with(e);
      }
   }
}
