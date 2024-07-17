package com.beatcraft.render;

import com.beatcraft.animation.AnimationState;
import com.beatcraft.animation.Easing;
import com.beatcraft.beatmap.data.GameplayObject;
import com.beatcraft.utils.MathUtil;
import com.beatcraft.utils.NoteMath;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.*;
import org.joml.Math;

public abstract class PhysicalGameplayObject<T extends GameplayObject> extends WorldRenderer {
    private static final float JUMP_FAR_Z = 500;
    private static final float JUMP_SECONDS = 0.4f;
    protected static final float SIZE_SCALAR = 0.5f;
    protected static final Vector3f WORLD_OFFSET = new Vector3f(0, 0.8f, 1f);
    private final float jumpBeats;
    private final Quaternionf spawnQuaternion = SpawnQuaternionPool.getRandomQuaternion();
    protected Quaternionf baseRotation = new Quaternionf();
    private Quaternionf laneRotation;
    private Quaternionf lookRotation = new Quaternionf();
    protected T data;
    protected NoteMath.Jumps jumps;
    protected boolean despawned = false;

    PhysicalGameplayObject(T data) {
        this.data = data;

        float bpm = BeatmapPlayer.currentInfo.getBpm();
        this.jumps = NoteMath.getJumps(data.getNjs(), data.getOffset(), bpm);
        this.jumpBeats = MathUtil.secondsToBeats(JUMP_SECONDS, bpm);
    }

    private Vector3f getPlayerHeadPosition() {
        return new Vector3f(0, 1.62f, 0);
    }

    public float getSpawnBeat() {
        return getData().getBeat() - jumps.halfDuration();
    }

    public float getJumpOutBeat() {
        return getData().getBeat() + jumps.halfDuration() * 0.5f;
    }

    public float getDespawnBeat() {
        return getData().getBeat() + jumps.halfDuration();
    }

    public float getSpawnPosition() {
        return jumps.jumpDistance() / 2;
    }

    public float getJumpOutPosition() {
        return jumps.jumpDistance() * -0.25f;
    }

    private void despawn() {
        despawned = true;
    }

    public boolean isDespawned() {
        return despawned;
    }

    public void reset() {
        despawned = false;
    }

    @Override
    public boolean shouldRender() {
        if (isDespawned()) {
            return false;
        }

        float margin = MathUtil.secondsToBeats(JUMP_SECONDS, BeatmapPlayer.currentInfo.getBpm());
        return BeatmapPlayer.getCurrentBeat() >= getSpawnBeat() - margin;
    }

    protected Vector3f getJumpsPosition(float lifetime, float time) {
        Vector2f xy = getJumpsXY(lifetime);
        return new Vector3f(xy.x, xy.y, getJumpsZ(time));
    }

    protected Vector2f getJumpsXY(float lifetime) {
        float reverseSpawnTime = 1 - Math.abs(lifetime - 0.5f) * 2;
        float jumpTime = Easing.easeOutQuad(reverseSpawnTime);
        Vector2f grid = get2DPosition();
        grid.y = Math.lerp(doNoteGravity() ? -0.3f: grid.y - 0.3f, grid.y, jumpTime);
        return grid;
    }

    protected float getJumpsZ(float time) {
        float spawnPosition = getSpawnPosition();
        float jumpOutPosition = getJumpOutPosition();

        float spawnBeat = getSpawnBeat();
        float jumpOutBeat = getJumpOutBeat();

        // jumps
        if (time < spawnBeat) {
            // jump in
            float percent = MathUtil.inverseLerp(spawnBeat - jumpBeats, spawnBeat, time);
            return Math.lerp(JUMP_FAR_Z, spawnPosition, percent);
        } else if (time < jumpOutBeat) {
            // in between
            float percent = MathUtil.inverseLerp(spawnBeat, jumpOutBeat, time);
            return Math.lerp(spawnPosition, jumpOutPosition, percent);
        } else {
            // jump out
            float percent = MathUtil.inverseLerp(jumpOutBeat, getDespawnBeat(), time);
            percent *= percent; // bullshit parabola or something
            return Math.lerp(jumpOutPosition, -JUMP_FAR_Z, percent);
        }
    }

    protected Vector2f get2DPosition() {
        float x = (this.getData().getX() - 1.5f) * 0.6f;
        float y = (this.getData().getY()) * 0.6f;
        return new Vector2f(x, y);
    }

    protected float getLifetime(float time) {
        float lifetime = MathUtil.inverseLerp(getSpawnBeat(), getDespawnBeat(), time);
        return MathUtil.clamp01(lifetime);
    }

    protected float getSpawnLifetime(float lifetime) {
        return MathUtil.clamp01(lifetime * 2);
    }

    // Converts world space to the object's local space
    protected Matrix4f getMatrix(float time, AnimationState animationState) {
        Matrix4f m = new Matrix4f();

        m.translate(getPlayerHeadPosition().x, 0, getPlayerHeadPosition().z);

        if (data.getWorldRotation() != null) {
            m.rotate(data.getWorldRotation());
        }

        if (animationState.getOffsetWorldRotation() != null) {
            m.rotate(animationState.getOffsetWorldRotation());
        }

        if (animationState.getOffsetPosition() != null) {
            m.translate(animationState.getOffsetPosition());
        }

        if (getLaneRotation() != null) {
            m.rotate(getLaneRotation());
        }

        applySpawnMatrix(time, m, animationState);

        MathUtil.reflectMatrixAcrossX(m); // Transform matrix from Beat Saber world space to Minecraft world space

        return m;
    }

    protected boolean doNoteLook() {
        return false;
    }
    protected boolean doNoteGravity() {
        return true;
    }

    protected void applySpawnMatrix(float time, Matrix4f m, AnimationState animationState) {
        float lifetime = getLifetime(time);
        float spawnLifetime = getSpawnLifetime(lifetime);

        Matrix4f jumpMatrix = new Matrix4f();

        Vector3f v = getJumpsPosition(lifetime, time);
        jumpMatrix.translate(v);

        m.translate(WORLD_OFFSET);

        if (doNoteLook()) {
            if (lifetime < 0.5) {
                Vector3f headPosition = getPlayerHeadPosition();
                headPosition = MathUtil.matrixTransformPoint3D(new Matrix4f(m).invert(), headPosition);
                headPosition = MathUtil.matrixTransformPoint3D(jumpMatrix, headPosition.mul(-1));
                Vector3f up = new Vector3f(0.0f, 0, 1);
                Quaternionf targetLookRotation = new Quaternionf().rotateTo(up, headPosition);
                lookRotation = new Quaternionf().slerp(targetLookRotation, spawnLifetime);
            }

            m.mul(jumpMatrix).rotate(lookRotation);
        } else {
            m.mul(jumpMatrix);
        }

        if (data.getLocalRotation() != null) {
            m.rotate(data.getLocalRotation());
        }

        if (animationState.getLocalRotation() != null) {
            m.rotate(animationState.getLocalRotation());
        }

        if (lifetime < 0.5) {
            m.rotate(getJumpsRotation(spawnLifetime));
        }
        else {
            m.rotate(baseRotation);
        }
    }

    protected Quaternionf getJumpsRotation(float spawnLifetime) {
        float rotationLifetime = MathUtil.clamp01(spawnLifetime / 0.3f);
        if (spawnLifetime == 0) {
            return baseRotation;
        }
        float rotationTime = Easing.easeOutQuad(rotationLifetime);
        return new Quaternionf().set(spawnQuaternion).slerp(baseRotation, rotationTime);
    }

    protected boolean jumpEnded(float beat) {
        return beat >= getDespawnBeat();
    }

    @Override
    protected void worldRender(MatrixStack matrices, VertexConsumer vertexConsumer) {
        float beat = BeatmapPlayer.getCurrentBeat();
        AnimationState animationState = data.getTrackContainer().getAnimationState();

        float spawnBeat = getSpawnBeat();
        float despawnBeat = getDespawnBeat();
        Float animationTime = animationState.getTime();
        if (beat >= spawnBeat && animationTime != null) {
            beat = Math.lerp(spawnBeat, despawnBeat, animationTime);
        }

        if (jumpEnded(beat)) {
            despawn();
            return;
        }

        Matrix4f matrix = getMatrix(beat, animationState);

        Matrix3f normalMatrix = new Matrix3f();
        matrix.get3x3(normalMatrix);
        matrices.multiplyPositionMatrix(matrix);
        matrices.peek().getNormalMatrix().mul(normalMatrix);

        matrices.scale(SIZE_SCALAR, SIZE_SCALAR, SIZE_SCALAR);
        matrices.translate(-0.5, -0.5, -0.5);

        objectRender(matrices, vertexConsumer, animationState);
    }

    abstract protected void objectRender(MatrixStack matrices, VertexConsumer vertexConsumer, AnimationState animationState);

    public T getData() {
        return data;
    }

    public Quaternionf getLaneRotation() {
        return laneRotation;
    }

    public void setLaneRotation(Quaternionf laneRotation) {
        this.laneRotation = laneRotation;
    }
}
