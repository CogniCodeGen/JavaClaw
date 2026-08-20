package com.javaclaw.plugins.deliverance;

import io.github.jbellis.jvector.vector.VectorUtil;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import io.teknek.deliverance.tensor.AbstractTensor;
import io.teknek.deliverance.tensor.operations.NaiveTensorOperations;

/** Deliverance 的标量安全实现上，以 JVector 加速最热点的点积路径。 */
final class JVectorTensorOperations extends NaiveTensorOperations {
    private static final VectorTypeSupport VECTORS =
            VectorizationProvider.getInstance().getVectorTypeSupport();

    @Override public String name() { return "JavaClaw JVector Operations"; }

    @Override
    public float dotProduct(AbstractTensor left, AbstractTensor right,
                            int leftOffset, int rightOffset, int length) {
        if (length <= 0) return 0;
        float[] leftValues = new float[length];
        float[] rightValues = new float[length];
        for (int index = 0; index < length; index++) {
            leftValues[index] = left.get(0, leftOffset + index);
            rightValues[index] = right.get(0, rightOffset + index);
        }
        return VectorUtil.dotProduct(VECTORS.createFloatVector(leftValues),
                VECTORS.createFloatVector(rightValues));
    }
}
