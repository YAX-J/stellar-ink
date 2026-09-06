export const fmt = (n) => n.toLocaleString('en-US')

export const readMinutes = (w) => Math.ceil(w / 400)

export const kWords = (w) => `${(w / 1000).toFixed(1)}K 字`
