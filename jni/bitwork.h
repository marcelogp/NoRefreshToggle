#include <stdint.h>


// Define some masks so we don't have to calculate them later.
const uint32_t masks[32] = {
    0x00000001,
    0x00000003,
    0x00000007,
    0x0000000f,
    0x0000001f,
    0x0000003f,
    0x0000007f,
    0x000000ff,
    0x000001ff,
    0x000003ff,
    0x000007ff,
    0x00000fff,
    0x00001fff,
    0x00003fff,
    0x00007fff,
    0x0000ffff,
    0x0001ffff,
    0x0003ffff,
    0x0007ffff,
    0x000fffff,
    0x001fffff,
    0x003fffff,
    0x007fffff,
    0x00ffffff,
    0x01ffffff,
    0x03ffffff,
    0x07ffffff,
    0x0fffffff,
    0x1fffffff,
    0x3fffffff,
    0x7fffffff,
    0xffffffff };


/**
 * @param pixels - A pointer to the memory with all the pixels.
 * @param index - The desired pixel, indexed from 0.
 * @param size - The size of a pixel, in bytes.
 */
uint32_t extractPixel(uint8_t * pixels, uint32_t index, uint8_t size) {
    // pix_ptr points to the low byte of the pixel and is not necessarily
    // aligned.
    uint8_t * pix_ptr = pixels + (index * size);
    uint8_t misalignment = (uint32_t)pix_ptr % 4;

    // Given that pixels are no more than four bytes, each pixel will have some
    // data in the 32-bit word at this address and may overflow to the next.
    uint32_t * lower_word_ptr = (uint32_t *)(pix_ptr - misalignment);
    uint8_t overflow = misalignment + size <= 4 ? 0 : (misalignment + size) % 4;

    uint32_t ret = *lower_word_ptr;
    ret >>= misalignment * 8;
    ret &= masks[(size - overflow) * 8 - 1];

    if (overflow > 0) {
        // There are relevant bits in the next word. Mask them out and add them
        // to ret.
        uint32_t top = *(lower_word_ptr + 1);
        top &= masks[overflow * 8 - 1];
        top <<= (size - overflow) * 8;
        ret += top;
    }

    return ret;
}


/**
 * @param in - The value of the input pixel.
 * @param offsets - An array of four bytes representing the offset of each
 *      color in the input pixel. This will be interpreted as [b, g, r, a].
 * @param sizes - An array of four bytes representing how many bits each
 *      color occupies in the input pixel. This will be interpreted as
 *      [b, g, r, a].
 * @return The input pixel formatted as an ARGB_8888 int.
 */
uint32_t formatPixel(uint32_t in, int * offsets, int * sizes) {
    // Ignore whatever alpha information we were passed in and make the output
    // pixel opaque.
    uint8_t out[4] = {0x00, 0x00, 0x00, 0xff};
    uint32_t mask;

    for (int color = 0; color < 3; ++color) {
        mask = masks[sizes[color] - 1];

        // Extract the desired bits from in, then shift them up if we have
        // less than a full byte of information.
        out[color] = (in >> offsets[color]) & mask;
        out[color] <<= 8 - sizes[color];
    }

    // Finally, combine the components, and that's a pixel.
    uint32_t ret = 0;
    for (int color = 3; color >= 0; --color) {
        ret <<= 8;
        ret |= out[color];
    }
    return ret;
}

