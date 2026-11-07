package com.esp;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Filled, see-through-wall boxes on storage blocks.
 * Orange = chest / trapped chest, purple = ender chest, pink = shulker box.
 * Render setup follows the Fabric docs' "Rendering in the World" example (no depth test pipeline).
 */
public final class StorageEsp {
    public static volatile boolean enabled = true;

    private record Box(float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b, float a) {}
    private static volatile List<Box> boxes = List.of();

    private static final float ALPHA = 0.45f;
    private static final int MAX_CHUNK_RADIUS = 10;

    private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(EspClient.ID, "pipeline/storage_esp_through_walls"))
                    .withDepthStencilState(Optional.empty())
                    .build());

    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static BufferBuilder buffer;
    private static MappableRingBuffer vertexBuffer;

    // ---------- extraction phase ----------
    static void extract(LevelExtractionContext context) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Entity center = mc.getCameraEntity();
        if (!enabled || level == null || center == null) { boxes = List.of(); return; }

        int radius = Math.min(mc.options.renderDistance().get(), MAX_CHUNK_RADIUS);
        int ccx = center.blockPosition().getX() >> 4;
        int ccz = center.blockPosition().getZ() >> 4;

        List<Box> out = new ArrayList<>();
        for (int cx = ccx - radius; cx <= ccx + radius; cx++) {
            for (int cz = ccz - radius; cz <= ccz + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    int x = be.getBlockPos().getX(), y = be.getBlockPos().getY(), z = be.getBlockPos().getZ();
                    if (be instanceof EnderChestBlockEntity) {          // purple
                        out.add(new Box(x + .0625f, y, z + .0625f, x + .9375f, y + .875f, z + .9375f, .60f, .20f, 1.00f, ALPHA));
                    } else if (be instanceof ChestBlockEntity) {        // orange (also trapped chests)
                        out.add(new Box(x + .0625f, y, z + .0625f, x + .9375f, y + .875f, z + .9375f, 1.00f, .55f, .00f, ALPHA));
                    } else if (be instanceof ShulkerBoxBlockEntity) {   // pink
                        out.add(new Box(x, y, z, x + 1f, y + 1f, z + 1f, 1.00f, .40f, .80f, ALPHA));
                    }
                }
            }
        }
        boxes = out;
    }

    // ---------- drawing phase ----------
    static void renderAndDraw(LevelRenderContext context) {
        List<Box> list = boxes;
        if (!enabled || list.isEmpty()) return;

        var matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        buffer = new BufferBuilder(ALLOCATOR, FILLED_THROUGH_WALLS.getVertexFormatMode(), FILLED_THROUGH_WALLS.getVertexFormat());
        Matrix4fc pose = matrices.last().pose();
        for (Box b : list) addBox(pose, buffer, b);
        matrices.popPose();

        drawFilledThroughWalls(Minecraft.getInstance(), FILLED_THROUGH_WALLS);
    }

    private static void v(Matrix4fc m, BufferBuilder b, float x, float y, float z, Box c) {
        b.addVertex(m, x, y, z).setColor(c.r(), c.g(), c.b(), c.a());
    }

    private static void addBox(Matrix4fc m, BufferBuilder b, Box c) {
        float x0 = c.x0(), y0 = c.y0(), z0 = c.z0(), x1 = c.x1(), y1 = c.y1(), z1 = c.z1();
        // front
        v(m, b, x0, y0, z1, c); v(m, b, x1, y0, z1, c); v(m, b, x1, y1, z1, c); v(m, b, x0, y1, z1, c);
        // back
        v(m, b, x1, y0, z0, c); v(m, b, x0, y0, z0, c); v(m, b, x0, y1, z0, c); v(m, b, x1, y1, z0, c);
        // left
        v(m, b, x0, y0, z0, c); v(m, b, x0, y0, z1, c); v(m, b, x0, y1, z1, c); v(m, b, x0, y1, z0, c);
        // right
        v(m, b, x1, y0, z1, c); v(m, b, x1, y0, z0, c); v(m, b, x1, y1, z0, c); v(m, b, x1, y1, z1, c);
        // top
        v(m, b, x0, y1, z1, c); v(m, b, x1, y1, z1, c); v(m, b, x1, y1, z0, c); v(m, b, x0, y1, z0, c);
        // bottom
        v(m, b, x0, y0, z0, c); v(m, b, x1, y0, z0, c); v(m, b, x1, y0, z1, c); v(m, b, x0, y0, z1, c);
    }

    private static void drawFilledThroughWalls(Minecraft client, RenderPipeline pipeline) {
        MeshData built = buffer.buildOrThrow();
        MeshData.DrawState state = built.drawState();
        VertexFormat format = state.format();

        GpuBuffer vertices = upload(state, format, built);
        draw(client, pipeline, built, state, vertices, format);

        vertexBuffer.rotate();
        buffer = null;
    }

    private static GpuBuffer upload(MeshData.DrawState state, VertexFormat format, MeshData built) {
        int size = state.vertexCount() * format.getVertexSize();
        if (vertexBuffer == null || vertexBuffer.size() < size) {
            if (vertexBuffer != null) vertexBuffer.close();
            vertexBuffer = new MappableRingBuffer(() -> EspClient.ID + " storage esp",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, size);
        }
        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView view = enc.mapBuffer(
                vertexBuffer.currentBuffer().slice(0, built.vertexBuffer().remaining()), false, true)) {
            MemoryUtil.memCopy(built.vertexBuffer(), view.data());
        }
        return vertexBuffer.currentBuffer();
    }

    private static void draw(Minecraft client, RenderPipeline pipeline, MeshData built,
                             MeshData.DrawState state, GpuBuffer vertices, VertexFormat format) {
        GpuBuffer indices;
        VertexFormat.IndexType indexType;
        if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
            built.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
            indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(built.indexBuffer());
            indexType = built.drawState().indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer idx = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
            indices = idx.getBuffer(state.indexCount());
            indexType = idx.type();
        }

        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> EspClient.ID + " storage esp rendering",
                client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indices, indexType);
            pass.drawIndexed(0, 0, state.indexCount(), 1);
        }
        built.close();
    }

    static void close() {
        ALLOCATOR.close();
        if (vertexBuffer != null) { vertexBuffer.close(); vertexBuffer = null; }
    }
}
