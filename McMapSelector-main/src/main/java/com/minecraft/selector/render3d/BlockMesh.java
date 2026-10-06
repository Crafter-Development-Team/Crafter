package com.minecraft.selector.render3d;

import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/**
 * 方块网格，用于渲染 Minecraft 方块
 */
public class BlockMesh {
    private int vao;
    private int vbo;
    private int ebo;
    private int vertexCount;
    private int indexCount;
    
    private List<Float> vertices = new ArrayList<>();
    private List<Float> colors = new ArrayList<>();
    private List<Integer> indices = new ArrayList<>();
    
    public BlockMesh() {
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();
    }
    
    /**
     * 添加一个方块到网格
     */
    public void addBlock(float x, float y, float z, float size, int color) {
        int baseIndex = vertices.size() / 3;
        
        // 提取 RGB 颜色
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        
        // 添加 8 个顶点
        float[][] corners = {
            {x, y, z},
            {x + size, y, z},
            {x + size, y + size, z},
            {x, y + size, z},
            {x, y, z + size},
            {x + size, y, z + size},
            {x + size, y + size, z + size},
            {x, y + size, z + size}
        };
        
        for (float[] corner : corners) {
            vertices.add(corner[0]);
            vertices.add(corner[1]);
            vertices.add(corner[2]);
            colors.add(r);
            colors.add(g);
            colors.add(b);
        }
        
        // 添加 12 个三角形（6 个面，每个面 2 个三角形）
        int[][] faces = {
            {0, 1, 2, 0, 2, 3},  // 前面
            {4, 6, 5, 4, 7, 6},  // 后面
            {0, 3, 7, 0, 7, 4},  // 左面
            {1, 5, 6, 1, 6, 2},  // 右面
            {3, 2, 6, 3, 6, 7},  // 顶面
            {0, 4, 5, 0, 5, 1}   // 底面
        };
        
        for (int[] face : faces) {
            for (int idx : face) {
                indices.add(baseIndex + idx);
            }
        }
    }
    
    /**
     * 构建网格
     */
    public void build() {
        System.out.println("构建网格: 顶点数=" + vertices.size()/3 + ", 索引数=" + indices.size());
        
        GL30.glBindVertexArray(vao);
        
        // 顶点数据
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.size());
        for (float v : vertices) {
            vertexBuffer.put(v);
        }
        vertexBuffer.flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        
        // 位置属性
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 24, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // 颜色属性
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 24, 12);
        GL20.glEnableVertexAttribArray(1);
        
        // 索引数据
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.size());
        for (int idx : indices) {
            indexBuffer.put(idx);
        }
        indexBuffer.flip();
        
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);
        
        indexCount = indices.size();
        
        GL30.glBindVertexArray(0);
        
        System.out.println("网格构建完成: indexCount=" + indexCount);
    }
    
    /**
     * 渲染网格
     */
    public void render() {
        GL30.glBindVertexArray(vao);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
    }
    
    /**
     * 清空网格数据
     */
    public void clear() {
        vertices.clear();
        colors.clear();
        indices.clear();
    }
    
    /**
     * 删除网格
     */
    public void delete() {
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        GL15.glDeleteBuffers(ebo);
    }
}
