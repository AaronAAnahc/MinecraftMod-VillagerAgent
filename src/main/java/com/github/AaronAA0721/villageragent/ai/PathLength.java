package com.github.AaronAA0721.villageragent.ai;

import java.util.function.IntToDoubleFunction;

/** Length along all route segments, not distance between route endpoints. */
public final class PathLength {
    private PathLength() {}
    public static double measure(double x, double y, double z, int count,
                                 IntToDoubleFunction xs, IntToDoubleFunction ys, IntToDoubleFunction zs) {
        if (count <= 0) return Double.POSITIVE_INFINITY;
        double total=0;
        for(int i=0;i<count;i++) {
            double nx=xs.applyAsDouble(i),ny=ys.applyAsDouble(i),nz=zs.applyAsDouble(i);
            double dx=nx-x,dy=ny-y,dz=nz-z;
            total+=Math.sqrt(dx*dx+dy*dy+dz*dz); x=nx;y=ny;z=nz;
        }
        return Double.isFinite(total)?total:Double.POSITIVE_INFINITY;
    }
}
