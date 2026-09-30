package zombie.radio.devices;

import fmod.fmod.FMODManager;
import fmod.javafmod;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import zombie.audio.BaseSoundEmitter;
import zombie.audio.FMODParameterUtils;
import zombie.audio.GameSound;
import zombie.audio.GameSoundClip;
import zombie.iso.IsoObject;
import zombie.network.GameServer;

// Injected into projectzomboid.jar by ZomboidRadioPatcher. Lives in DeviceData's package to reach its protected fields.
public final class ZomboidRadioHook {
   public static final int CHANNEL = 91800; // kHz; the game only tunes multiples of 200
   public static final String URL = "http://lpm.streamguys1.com/wfpk-mp3"; // WFPK 91.9 Louisville, KY
   private static final String NAME = "ZomboidRadioStream";
   private static final Set<Long> streams = ConcurrentHashMap.newKeySet();
   private static final Map<Long, Long> streamChannels = new ConcurrentHashMap<>();
   private static GameSoundClip clip;
   private static long retryAt;

   // Replaces FMODManager.loadSound in FMODSoundEmitter.addSound: http clips become fresh net streams (3D | CREATESTREAM).
   public static long loadSound(FMODManager manager, String file) {
      if (!file.startsWith("http")) {
         return manager.loadSound(file);
      }

      long sound = javafmod.FMOD_System_CreateSound(file, 144L);
      if (sound != 0L) {
         streams.add(sound);
      }

      return sound;
   }

   // Replaces javafmod.FMOD_System_PlaySound in FMODSoundEmitter.addSound: remember which channel plays a stream.
   public static long playSound(long sound, boolean paused) {
      long channel = javafmod.FMOD_System_PlaySound(sound, paused);
      if (streams.remove(sound)) {
         streamChannels.put(channel, sound);
      }

      return channel;
   }

   // Replaces javafmod.FMOD_Channel_Stop in FMODSoundEmitter$FileSound.stop: close the stream's connection too.
   public static int channelStop(long channel) {
      int result = javafmod.FMOD_Channel_Stop(channel);
      Long sound = streamChannels.remove(channel);
      if (sound != null) {
         javafmod.FMOD_Sound_Release(sound);
      }

      return result;
   }

   // Called first thing in DeviceData.updateEmitter; true means handled, skip the game's static/talk loop.
   public static boolean updateEmitter(DeviceData d) {
      if (GameServer.server || !d.isTurnedOn || d.channel != CHANNEL || d.isTelevision) {
         return false;
      }

      IsoObject o = d.getIsoObject();
      if (o == null || FMODParameterUtils.getClosestListenerDistanceSquared(o.getX(), o.getY(), o.getZ()) > 256.0F) {
         return false;
      }

      d.parameterList.update();
      d.setEmitterAndPos();
      BaseSoundEmitter emitter = d.emitter;
      if (emitter == null) {
         return true;
      }

      if (!emitter.isPlaying(NAME)) {
         if (d.radioLoopSound > 0L) {
            emitter.stopSound(d.radioLoopSound);
         }

         d.radioLoopSound = 0L;
         // ponytail: FMOD connects synchronously (short hitch on tune-in); a dead stream retries every 30s, not every frame
         if (System.currentTimeMillis() >= retryAt) {
            d.radioLoopSound = emitter.playClip(clip(), null);
            if (d.radioLoopSound == 0L) {
               retryAt = System.currentTimeMillis() + 30000L;
            } else {
               emitter.setVolume(d.radioLoopSound, d.deviceVolume);
            }
         }
      }

      emitter.tick();
      return true;
   }

   private static GameSoundClip clip() {
      if (clip == null) {
         GameSound gameSound = new GameSound();
         gameSound.name = NAME;
         clip = new GameSoundClip(gameSound);
         clip.file = URL;
         clip.distanceMin = 2.0F;
         clip.distanceMax = 16.0F;
         gameSound.clips.add(clip);
      }

      return clip;
   }
}
