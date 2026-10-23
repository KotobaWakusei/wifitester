/*
 * 一加15强制注入内存补丁
 * 目标: 绕过QCACLD 3.0固件注入限制
 * 编译: gcc -o memory_injection memory_injection.c -lpthread
 * 运行: adb push memory_injection /data/local/tmp && adb shell su -c ./memory_injection
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <errno.h>

// 固件过滤函数特征码（需要根据实际固件调整）
static unsigned char filter_function_signature[] = {
    0x55, 0x48, 0x89, 0xE5, 0x48, 0x83, 0xEC, 0x20, 0x48, 0x8B, 0x05
};

#define SIGNATURE_LENGTH 11
#define PAGE_SIZE 4096

// 内存搜索函数
void* find_pattern_in_memory(int mem_fd, const unsigned char* pattern, size_t pattern_len) {
    off_t file_size = lseek(mem_fd, 0, SEEK_END);
    lseek(mem_fd, 0, SEEK_SET);
    
    unsigned char* buffer = malloc(PAGE_SIZE);
    if (!buffer) {
        perror("malloc failed");
        return NULL;
    }
    
    off_t offset = 0;
    while (offset < file_size) {
        ssize_t bytes_read = read(mem_fd, buffer, PAGE_SIZE);
        if (bytes_read <= 0) break;
        
        for (size_t i = 0; i <= bytes_read - pattern_len; i++) {
            if (memcmp(&buffer[i], pattern, pattern_len) == 0) {
                void* result = (void*)(offset + i);
                free(buffer);
                return result;
            }
        }
        
        offset += PAGE_SIZE;
        lseek(mem_fd, offset, SEEK_SET);
    }
    
    free(buffer);
    return NULL;
}

// 内存补丁函数
int patch_memory(int mem_fd, void* target_addr, const unsigned char* patch, size_t patch_size) {
    // 计算页面对齐
    off_t page_offset = (off_t)target_addr & ~(PAGE_SIZE - 1);
    size_t page_offset_in_target = (off_t)target_addr - page_offset;
    
    // 映射页面
    void* mapped_page = mmap(NULL, PAGE_SIZE, PROT_READ | PROT_WRITE, MAP_PRIVATE, mem_fd, page_offset);
    if (mapped_page == MAP_FAILED) {
        perror("mmap failed");
        return -1;
    }
    
    // 应用补丁
    memcpy((char*)mapped_page + page_offset_in_target, patch, patch_size);
    
    // 刷新缓存
    msync(mapped_page, PAGE_SIZE, MS_SYNC);
    
    // 解除映射
    munmap(mapped_page, PAGE_SIZE);
    
    return 0;
}

int main() {
    printf("=== 一加15强制注入内存补丁 ===\n");
    
    // 打开/dev/mem
    int mem_fd = open("/dev/mem", O_RDWR);
    if (mem_fd < 0) {
        perror("Failed to open /dev/mem");
        printf("尝试使用/dev/kmem...\n");
        mem_fd = open("/dev/kmem", O_RDWR);
        if (mem_fd < 0) {
            perror("Failed to open /dev/kmem");
            return 1;
        }
    }
    
    printf("已打开内存设备\n");
    
    // 搜索固件过滤函数
    printf("搜索固件过滤函数...\n");
    void* filter_func = find_pattern_in_memory(mem_fd, filter_function_signature, SIGNATURE_LENGTH);
    
    if (!filter_func) {
        printf("未找到固件过滤函数\n");
        close(mem_fd);
        return 1;
    }
    
    printf("找到固件过滤函数地址: %p\n", filter_func);
    
    // 创建补丁：将过滤函数的返回值改为允许
    unsigned char patch[] = {
        0xB8, 0x00, 0x00, 0x00, 0x00,  // mov eax, 0 (返回成功)
        0xC3                            // ret
    };
    
    // 应用补丁
    printf("应用内存补丁...\n");
    if (patch_memory(mem_fd, filter_func, patch, sizeof(patch)) == 0) {
        printf("✅ 内存补丁成功应用\n");
    } else {
        printf("❌ 内存补丁应用失败\n");
        close(mem_fd);
        return 1;
    }
    
    // 尝试设置注入参数
    FILE* inject_param = fopen("/sys/module/qca_cld3_peach_v2/parameters/inject", "w");
    if (inject_param) {
        fprintf(inject_param, "1");
        fclose(inject_param);
        printf("✅ 注入参数已设置\n");
    } else {
        printf("❌ 无法设置注入参数\n");
    }
    
    // 尝试设置promiscuous模式
    FILE* promiscuous_param = fopen("/sys/module/qca_cld3_peach_v2/parameters/promiscuous", "w");
    if (promiscuous_param) {
        fprintf(promiscuous_param, "1");
        fclose(promiscuous_param);
        printf("✅ Promiscuous模式已启用\n");
    } else {
        printf("❌ 无法启用promiscuous模式\n");
    }
    
    close(mem_fd);
    printf("=== 补丁应用完成 ===\n");
    printf("请重启WiFi服务或重启设备以使更改生效\n");
    
    return 0;
}